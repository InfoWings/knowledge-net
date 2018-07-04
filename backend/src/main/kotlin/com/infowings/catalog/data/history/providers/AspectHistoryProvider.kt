package com.infowings.catalog.data.history.providers

import com.infowings.catalog.common.*
import com.infowings.catalog.data.aspect.AspectService
import com.infowings.catalog.data.aspect.OpenDomain
import com.infowings.catalog.data.history.*
import com.infowings.catalog.data.reference.book.ReferenceBookDao
import com.infowings.catalog.data.subject.SubjectDao
import com.infowings.catalog.external.logTime
import com.infowings.catalog.loggerFor
import com.infowings.catalog.storage.ASPECT_CLASS
import com.infowings.catalog.storage.ASPECT_PROPERTY_CLASS
import com.infowings.catalog.storage.id

private val logger = loggerFor<AspectHistoryProvider>()

private data class DataWithSnapshot(val data: AspectData, val snapshot: Snapshot, val propSnapshots: Map<String, Snapshot>) {
    fun propertySnapshot(id: String) = propSnapshots[id] ?: Snapshot()
}

private val changeNamesConvert = mapOf(
    AspectField.NAME.name to "Name",
    AspectField.BASE_TYPE.name to "Base type",
    AspectField.DESCRIPTION.name to "Description",
    AspectField.MEASURE.name to "Measure",
    AspectField.SUBJECT to "Subject",
    AspectField.REFERENCE_BOOK to "Reference book"
)

private val removedSubject = SubjectData(id = "", name = "Subject removed", description = "")

private data class AggregationContext(val aspectById: Map<String, AspectData>,
                                      val subjectById: Map<String, SubjectData>,
                                      val refBookNames: Map<String, String>,
                                      val before: DataWithSnapshot) {
    fun aspectName(id: String): String? = aspectById[id]?.name
    fun subjectName(id: String): String? = subjectById[id]?.name
}

private fun DataAware.cardinalityLabel() = this.dataItem(AspectPropertyField.CARDINALITY.name)?.let {
    PropertyCardinality.valueOf(it).label
}

private fun DataAware.toAspectPropertyData(id: String) = AspectPropertyData(id = id,
    name = dataOrEmpty(AspectPropertyField.NAME.name),
    aspectId = dataOrEmpty(AspectPropertyField.ASPECT.name),
    cardinality = dataOrEmpty(AspectPropertyField.CARDINALITY.name),
    description = dataItem(AspectPropertyField.DESCRIPTION.name),
    version = dataItem("_version")?.toInt()?:-1)

private fun DataAware.toAspectData(properties: List<AspectPropertyData>, event: HistoryEventData, refBookName: String?, subject: SubjectData?): AspectData {
    val baseType = dataItem(AspectField.BASE_TYPE.name)
    return AspectData(
        id = null,
        name = dataOrEmpty(AspectField.NAME.name),
        description = dataItem(AspectField.DESCRIPTION.name),
        baseType = baseType,
        domain = baseType?.let { OpenDomain(BaseType.restoreBaseType(it)).toString() },
        measure = dataItem(AspectField.MEASURE.name),
        properties = properties,
        version = event.version,
        deleted = event.type.isDelete(),
        refBookName = refBookName,
        subject = subject
    )
}


private fun createPropertyDelta(propertyFact: HistoryFact, context: AggregationContext): FieldDelta? {
    val name = propertyFact.payload.dataOrEmpty(AspectPropertyField.NAME.name)
    val cardinality = propertyFact.payload.cardinalityLabel()
    val aspectId = propertyFact.payload.dataOrEmpty(AspectPropertyField.ASPECT.name)
    return FieldDelta("Property $name", null, "$name ${context.aspectName(aspectId) ?: "'Aspect removed'"} : [$cardinality]")
}

private fun updatePropertyDelta(propertyFact: HistoryFact, context: AggregationContext): FieldDelta?  = when (propertyFact.payload.isEmpty()) {
    true -> null
    false -> {
        val name = propertyFact.payload.dataOrEmpty(AspectPropertyField.NAME.name)
        val prevSnapshot: Snapshot = context.before.propertySnapshot(propertyFact.event.entityId)

        val prevName = prevSnapshot.dataOrEmpty(AspectPropertyField.NAME.name)
        val prevCardinality = prevSnapshot.cardinalityLabel()
        val cardinality = propertyFact.payload.cardinalityLabel()
        val aspectId = prevSnapshot.dataOrEmpty(AspectPropertyField.ASPECT.name)

        FieldDelta(
            "Property $name",
            "$prevName ${context.aspectName(aspectId)} : [$prevCardinality]",
            "$name ${context.aspectName(aspectId)} : [$cardinality]"
        )
    }
}

private fun deletePropertyDelta(propertyFact: HistoryFact, context: AggregationContext): FieldDelta? {
    val name = propertyFact.payload.dataItem(AspectPropertyField.NAME.name)
    val prevSnapshot: Snapshot = context.before.propertySnapshot(propertyFact.event.entityId)
    val prevName =  prevSnapshot.dataOrEmpty(AspectPropertyField.NAME.name)
    val prevCardinality =  prevSnapshot.cardinalityLabel()

    val aspectId = prevSnapshot.data.get(AspectPropertyField.ASPECT.name) ?: ""
    return FieldDelta("Property ${name ?: " "}",
        "$prevName ${context.aspectName(aspectId) ?: "'Aspect removed'"} : [$prevCardinality]",
        null)
}

private object DeltaProducers {
    fun changeLink(key: String, aspectFact: HistoryFact, context: AggregationContext): FieldDelta? {
        val beforeId = aspectFact.payload.removedSingleFor(key)?.toString()
        val afterId = aspectFact.payload.addedSingleFor(key)?.toString()

        val beforeName = beforeId?.let {
            when (key) {
                AspectField.SUBJECT -> context.subjectName(beforeId)?:"Subject removed"
                AspectField.REFERENCE_BOOK -> context.refBookNames[afterId]
                else -> null
            }
        } ?: "???"
        val afterName = afterId?.let {
            when (key) {
                AspectField.SUBJECT -> context.subjectName(afterId)?:"Subject removed"
                AspectField.REFERENCE_BOOK -> context.refBookNames[afterId]
                else -> null
            }
        } ?: "???"

        return FieldDelta(changeNamesConvert.getOrDefault(key, key), beforeName, afterName)
    }


    fun addLink(key: String, aspectFact: HistoryFact, context: AggregationContext): FieldDelta? = when (key) {
        AspectField.PROPERTY -> null
        else -> {
            val afterId = aspectFact.payload.addedSingleFor(key)?.toString()
            val afterName = afterId?.let {
                when (key) {
                    AspectField.SUBJECT -> context.subjectName(afterId)?:"Subject removed"
                    AspectField.REFERENCE_BOOK -> context.refBookNames[afterId]
                    else -> null
                }
            } ?: "???"

            FieldDelta(
                changeNamesConvert.getOrDefault(key, key),
                context.before.snapshot.links[key]?.first()?.toString(),
                afterName
            )
        }
    }
}

private val propertyDeltaCreators = listOf(
    EventType.UPDATE to ::updatePropertyDelta,
    EventType.DELETE to ::deletePropertyDelta,
    EventType.SOFT_DELETE to ::deletePropertyDelta,
    EventType.CREATE to ::createPropertyDelta
)

class AspectHistoryProvider(
    private val historyService: HistoryService,
    private val aspectDeltaConstructor: AspectDeltaConstructor,
    private val refBookDao: ReferenceBookDao,
    private val subjectDao: SubjectDao,
    private val aspectService: AspectService
) {
    fun getAllHistory(): List<AspectHistory> {
        val bothFacts = historyService.allTimeline(listOf(ASPECT_CLASS, ASPECT_PROPERTY_CLASS))

        val factsByClass = bothFacts.groupBy { it.event.entityClass }
        val aspectFacts = factsByClass[ASPECT_CLASS].orEmpty()
        val aspectFactsByEntity = aspectFacts.groupBy { it.event.entityId }
        val propertyFacts = factsByClass[ASPECT_PROPERTY_CLASS].orEmpty()
        val propertyFactsBySession = propertyFacts.groupBy { it.event.sessionId }
        val propertySnapshots = propertyFacts.map { it.event.entityId to MutableSnapshot() }.toMap()

        val refBookIds = aspectFacts.linksOfType(AspectField.REFERENCE_BOOK)

        val refBookNames = logTime(logger, "extracting ref book names") {
            refBookDao.find(refBookIds.toList()).groupBy {it.id}.mapValues { it.value.first().value }
        }

        val subjectIds = aspectFacts.linksOfType(AspectField.SUBJECT)

        val subjectById = logTime(logger, "extracting subjects") {
            subjectDao.find(subjectIds.toList()).groupBy {it.id}.mapValues { (_, elems) ->
                val subjectVertex = elems.first()
                SubjectData(id = subjectVertex.id, name = subjectVertex.name, description = subjectVertex.description, version = subjectVertex.version)
            }
        }

        val aspectIds = propertyFacts.mapNotNull { fact ->
            fact.payload.data[AspectPropertyField.ASPECT.name]
        }.toSet()

        val aspectsData = aspectService.getAspectsWithDeleted(aspectIds.toList())
        val aspectsById: Map<String, AspectData> = aspectsData.map { it.id!! to it }.toMap()

        val events = logTime(logger, "processing aspect event groups") {
            aspectFactsByEntity.values.flatMap {  aspectFacts ->
                val snapshot = MutableSnapshot()

                val versionList = listOf(DataWithSnapshot(AspectData(id = null, name = ""), snapshot.immutable(), emptyMap())) + aspectFacts.map { aspectFact ->
                    if (!aspectFact.event.type.isDelete()) {
                        snapshot.apply(aspectFact.payload)
                        val propertyFacts = propertyFactsBySession[aspectFact.event.sessionId]
                        propertyFacts?.forEach { propertyFact ->
                            propertySnapshots[propertyFact.event.entityId]?.apply(propertyFact.payload)
                            propertySnapshots[propertyFact.event.entityId]?.data?.set("_version", propertyFact.event.version.toString())
                        }
                    }

                    val baseType = snapshot.data[AspectField.BASE_TYPE.name]

                    val properties = (snapshot.links[AspectField.PROPERTY]?.toSet() ?: emptySet()).map {
                        val propId = it.toString()
                        val propSnapshot = propertySnapshots[propId] ?: MutableSnapshot()
                        propSnapshot.toAspectPropertyData(propId)
                    }

                    val refBookName = snapshot.resolvedLink(AspectField.REFERENCE_BOOK) {
                        refBookNames[it]?: "???"
                    }
                    val subject = snapshot.resolvedLink(AspectField.SUBJECT) {
                        subjectById[it] ?: removedSubject
                    }

                    val aspectData = snapshot.toAspectData(properties, aspectFact.event, refBookName, subject)

                    val propertySnapshotsById = properties.map { property ->
                        val propertySnapshot = propertySnapshots[property.id] ?: MutableSnapshot()
                        property.id to propertySnapshot.immutable()
                    }.toMap()

                    DataWithSnapshot(aspectData, snapshot.immutable(), propertySnapshotsById)
                }

                val res = logTime(logger, "aspect diffs creation for aspect ${aspectFacts.firstOrNull()?.event?.entityId}") {
                    versionList.zipWithNext().zip(aspectFacts)
                        .map { (versionsPair, aspectFact) ->
                            val res: AspectHistory = aspectDeltaConstructor.createDiff(versionsPair.first.data, versionsPair.second.data, aspectFact)

                            val (before, after) = versionsPair

                            val propertyFactsByType = (propertyFactsBySession[aspectFact.event.sessionId]?: emptyList()).groupBy { it.event.type }

                            val context = AggregationContext(aspectsById, subjectById, refBookNames, before)

                            val propertyDeltas = propertyDeltaCreators.flatMap { (eventType, deltaCreator) ->
                                propertyFactsByType[eventType].orEmpty().mapNotNull { deltaCreator(it, context) }
                            }

                            val linksSplit: Split = aspectFact.payload.classifyLinks()

                            val replacedLinksDeltas = linksSplit.changed.mapNotNull { DeltaProducers.changeLink(it, aspectFact, context)}
                            val addedLinksDeltas = linksSplit.added.mapNotNull { DeltaProducers.addLink(it, aspectFact, context)}

                            val deltas = aspectFact.payload.data.map {
                                val emptyPlaceholder = if (it.key == AspectField.NAME.name) "" else null
                                FieldDelta(changeNamesConvert.getOrDefault(it.key, it.key),
                                    before.snapshot.data[it.key]?:emptyPlaceholder,
                                    if (aspectFact.event.type.isDelete()) null else after.snapshot.data[it.key])
                            } + replacedLinksDeltas + addedLinksDeltas + aspectFact.payload.removedLinks.mapNotNull {
                                if (it.key != AspectField.PROPERTY && !linksSplit.changed.contains(it.key)) {
                                    logger.info("r key: ${it.key}")
                                    logger.info("r before links: " + before.snapshot.links)
                                    logger.info("r after links: " + after.snapshot.links)
                                    logger.info("r before data: " + before.snapshot.data)
                                    logger.info("r after data: " + after.snapshot.data)
                                    val beforeId = before.snapshot.links[it.key]?.first()?.toString()
                                    val key = it.key
                                    logger.info("r beforeId: " + beforeId)
                                    val beforeName = beforeId?.let {
                                        when (key) {
                                            AspectField.SUBJECT -> subjectById[beforeId]?.name
                                            AspectField.REFERENCE_BOOK -> refBookNames[beforeId]
                                            else -> null
                                        }
                                    } ?: "???"
                                    val afterId = after.snapshot.links[it.key]?.first()?.toString()
                                    logger.info("afterId: " + afterId)
                                    val afterName = afterId?.let {
                                        when (key) {
                                            AspectField.SUBJECT -> subjectById[afterId]?.name
                                            AspectField.REFERENCE_BOOK -> refBookNames[afterId]
                                            else -> null
                                        }
                                    } ?: "???"

                                    FieldDelta(
                                        changeNamesConvert.getOrDefault(it.key, it.key),
                                        beforeName,
                                        null
                                    )
                                } else null
                            } + propertyDeltas


                            val res2 = AspectHistory(aspectFact.event, after.data.name, after.data.deleted,
                                AspectDataView(after.data, after.data.properties.mapNotNull {
                                    aspectsById[it.aspectId] ?: AspectData(it.aspectId, "'Aspect removed'", null)
                                }), if (aspectFact.event.type.isDelete()) deltas.filterNot { it.fieldName in setOf("Subject", "Reference book") } else deltas)

                            logger.info("42 res==res2: ${res==res2}")

                            res
                        }
                }

                return@flatMap res
            }
        }

        return events.sortedByDescending { it.event.timestamp }
    }
}
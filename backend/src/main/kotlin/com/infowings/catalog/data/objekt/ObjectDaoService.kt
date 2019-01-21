package com.infowings.catalog.data.objekt

import com.infowings.catalog.common.*
import com.infowings.catalog.common.objekt.ObjectCreateRequest
import com.infowings.catalog.common.objekt.ObjectUpdateRequest
import com.infowings.catalog.data.history.HISTORY_EDGE
import com.infowings.catalog.external.logTime
import com.infowings.catalog.loggerFor
import com.infowings.catalog.storage.*
import com.orientechnologies.orient.core.id.ORID
import com.orientechnologies.orient.core.id.ORecordId
import com.orientechnologies.orient.core.record.ODirection
import com.orientechnologies.orient.core.record.OEdge
import com.orientechnologies.orient.core.record.OVertex
import com.orientechnologies.orient.core.sql.executor.OResult
import java.math.BigDecimal
import java.time.Instant

data class ValueWithContext(val value: ObjectPropertyValueVertex,
                            val propertyId: String,
                            val objectId: String)

class ObjectDaoService(private val db: OrientDatabase) {
    fun newObjectVertex() = db.createNewVertex(OBJECT_CLASS).toObjectVertex()
    fun newObjectPropertyVertex() = db.createNewVertex(OBJECT_PROPERTY_CLASS).toObjectPropertyVertex()
    fun newObjectValueVertex() = db.createNewVertex(OBJECT_PROPERTY_VALUE_CLASS).toObjectPropertyValueVertex()

    private fun <T : OVertex> replaceEdge(vertex: OVertex, edgeClass: String, oldTarget: T?, newTarget: T?) {
        if (oldTarget?.identity != newTarget?.identity) {
            vertex.getEdges(ODirection.OUT, edgeClass).forEach { it.delete<OEdge>() }
            newTarget?.let {
                vertex.addEdge(it, edgeClass).save<OEdge>()
            }
        }
    }

    private fun extractObject(result: OResult, tsById: Map<String, Instant>): ObjectTruncated {
        val id: ORID = result.getProperty<ORID>("@rid")
        return ObjectTruncated(
            id,
            result.getProperty("name"),
            result.getProperty("guid"),
            result.getProperty("description"),
            result.getProperty("subjectName") ?: "UNKNOWN",
            result.getProperty("objectPropertiesCount"),
            lastUpdated = tsById.get(id.toString())?.epochSecond
        )
    }

    fun getTruncatedObjects(pattern: String = "") =
        logTime(logger, "request of all truncated objects") {
            transaction(db) {
                val queryTS =
                    "SELECT @rid, name, description, " +
                            " max(in('${OrientEdge.OBJECT_OF_OBJECT_PROPERTY.extName}').out('$HISTORY_EDGE').timestamp) as lastPropTS," +
                            " max(in('${OrientEdge.OBJECT_OF_OBJECT_PROPERTY.extName}').in('${OrientEdge.OBJECT_PROPERTY_OF_OBJECT_VALUE.extName}').out('$HISTORY_EDGE').timestamp) as lastValueTS," +
                            " max(out('$HISTORY_EDGE').timestamp) as lastTS " +
                            "FROM $OBJECT_CLASS group by @rid "
                val tsById = logTime(logger, "request of all timestamps") {
                    db.query(queryTS) {
                        it.map {
                            val id: ORID = it.getProperty("@rid")
                            val propTS: Instant? = it.getProperty("lastPropTS")
                            val objectTS: Instant? = it.getProperty("lastTS")
                            val valueTS: Instant? = it.getProperty("lastValueTS")


                            val latest = listOfNotNull(propTS, objectTS, valueTS).sorted().lastOrNull() ?: {
                                logger.info("no timestamps: $objectTS, $propTS $valueTS")
                                Instant.EPOCH
                            }.invoke()

                            id.toString() to latest
                        }.toMap()
                    }
                }

                logger.info("tsById: $tsById")

                if (pattern == "") {
                    val query =
                        "SELECT @rid, name, description, " +
                                "FIRST(OUT(\"${OrientEdge.GUID_OF_OBJECT.extName}\")).guid as guid, " +
                                "FIRST(OUT(\"$OBJECT_SUBJECT_EDGE\")).name as subjectName, " +
                                "IN(\"$OBJECT_OBJECT_PROPERTY_EDGE\").size() as objectPropertiesCount " +
                                "FROM $OBJECT_CLASS WHERE (deleted is NULL or deleted = false ) "
                    return@transaction db.query(query) { it.map { extractObject(it, tsById) } }.toList()
                } else {
                    fun luceneIdx(classType: String, attr: String) = "\"$classType.lucene.$attr\""
                    fun searchLucene(classType: String, attr: String, patternBinding: String) =
                        "( SEARCH_INDEX(${luceneIdx(classType, attr)}, :$patternBinding) = true)"

                    fun luceneQuery(text: String) = "($text~) ($text*) (*$text*)"
                    fun anyOfCond(conds: List<String>) = conds.joinToString(" or ", "(", ")")
                    fun textOrAllWildcard(text: String?): String = if (text == null || text.isBlank()) "*" else {
                        text.trim()
                    }

                    val queryByStr =
                        "SELECT @rid, name, description, \n" +
                                "FIRST(OUT(\"${OrientEdge.GUID_OF_OBJECT.extName}\")).guid as guid, \n" +
                                "FIRST(OUT(\"$OBJECT_SUBJECT_EDGE\")).name as subjectName, \n" +
                                "IN(\"$OBJECT_OBJECT_PROPERTY_EDGE\").size() as objectPropertiesCount \n" +
                                "FROM $OBJECT_CLASS \n" +
                                "      LET \$t = (SELECT OUT(\"${OrientEdge.OBJECT_PROPERTY_OF_OBJECT_VALUE.extName}\") AS prop, \n" +
                                "                        prop.OUT(\"${OrientEdge.OBJECT_OF_OBJECT_PROPERTY.extName}\").name AS obj_name, \n" +
                                "                        out(\"${OrientEdge.OBJECT_PROPERTY_OF_OBJECT_VALUE.extName}\") \n" +
                                "                           .out(\"${OrientEdge.OBJECT_OF_OBJECT_PROPERTY.extName}\").@rid AS obj_id,\n" +
                                "                         out(\"${OrientEdge.OBJECT_PROPERTY_OF_OBJECT_VALUE.extName}\").deleted AS prop_deleted,\n" +
                                "                    ${STR_TYPE_PROPERTY}, ${TYPE_TAG_PROPERTY}, deleted  FROM ${OrientClass.OBJECT_VALUE.extName}\n" +
                                "          WHERE ${TYPE_TAG_PROPERTY} = ${ScalarTypeTag.STRING.code}\n" +
                                "                 AND ${searchLucene(OrientClass.OBJECT_VALUE.extName, STR_TYPE_PROPERTY, "lq")}\n" +
                                "                 AND (deleted is null or deleted = false) AND (prop_deleted is null OR prop_deleted = false)\n" +
                                "          UNWIND obj_name, obj_id, prop_deleted) WHERE @rid IN \$t.obj_id  " +
                                " and (deleted is NULL or deleted = false ) "
                    val byStringValue = db.query(queryByStr, mapOf("lq" to luceneQuery(textOrAllWildcard(pattern)))) {
                        it.map { extractObject(it, tsById) }
                    }.toList()

                    val queryByRefBook =
                        "SELECT @rid, name, description, " +
                                "FIRST(OUT(\"${OrientEdge.GUID_OF_OBJECT.extName}\")).guid as guid, " +
                                "FIRST(OUT(\"$OBJECT_SUBJECT_EDGE\")).name as subjectName, " +
                                "IN(\"$OBJECT_OBJECT_PROPERTY_EDGE\").size() as objectPropertiesCount " +
                                "FROM $OBJECT_CLASS " +
                                "      LET \$t = (\n" +
                                "         SELECT @rid, \n" +
                                "                        OUT(\"${OrientEdge.OBJECT_PROPERTY_OF_OBJECT_VALUE.extName}\").deleted AS prop_deleted,\n" +
                                "                        OUT(\"${OrientEdge.OBJECT_PROPERTY_OF_OBJECT_VALUE.extName}\") \n" +
                                "                          .OUT(\"${OrientEdge.OBJECT_OF_OBJECT_PROPERTY.extName}\").@rid AS obj_id \n" +
                                "                        FROM ${OrientClass.OBJECT_VALUE.extName}\n" +
                                "          LET \$v=(SELECT  IN(\"${OrientEdge.OBJECT_VALUE_DOMAIN_ELEMENT.extName}\").@rid as rid\n" +
                                "             FROM ${OrientClass.REFBOOK_ITEM.extName} \n" +
                                "                       WHERE ${searchLucene(OrientClass.REFBOOK_ITEM.extName, "value", "lq")}" +
                                "                             AND  IN(\"${OrientEdge.OBJECT_VALUE_DOMAIN_ELEMENT.extName}\").@rid.size() > 0" +
                                "                             AND (deleted is null or deleted == false) UNWIND rid)" +
                                "              WHERE @rid in \$v.rid and (prop_deleted is null or prop_deleted == false) " +
                                "                                        and  (deleted is null or deleted == false)" +
                                " unwind prop_deleted, obj_id \n" +
                                " ) where @rid in \$t.obj_id  " +
                                " and (deleted is NULL or deleted = false ) "
                    val byRefBookValue = db.query(queryByRefBook, mapOf("lq" to luceneQuery(textOrAllWildcard(pattern)))) {
                        it.map { extractObject(it, tsById) }
                    }.toList()

                    val query =
                        "SELECT @rid, name, description, \n" +
                                "FIRST(OUT(\"${OrientEdge.GUID_OF_OBJECT.extName}\")).guid as guid, \n" +
                                "FIRST(OUT(\"$OBJECT_SUBJECT_EDGE\")).name as subjectName, \n" +
                                "IN(\"$OBJECT_OBJECT_PROPERTY_EDGE\").size() as objectPropertiesCount \n" +
                                "FROM $OBJECT_CLASS  WHERE \n" +
                                "${anyOfCond(listOf(searchLucene(OBJECT_CLASS, ATTR_NAME, "lq"), searchLucene(OBJECT_CLASS, ATTR_DESC, "lq")))}\n" +
                                " and (deleted is NULL or deleted = false ) \n"
                    val byNameDesc = db.query(query, mapOf("lq" to luceneQuery(textOrAllWildcard(pattern)))) {
                        it.map { extractObject(it, tsById) }
                    }.toList()
                    return@transaction byNameDesc + byStringValue + byRefBookValue
                }
            }
        }

    fun getSubValues(id: String): Set<ObjectPropertyValueVertex> = getSubValues(ORecordId(id))

    private fun getSubValues(id: ORID): Set<ObjectPropertyValueVertex> = transaction(db) {
        db.query("SELECT FROM (TRAVERSE IN(\"$OBJECT_VALUE_OBJECT_VALUE_EDGE\") FROM :id)", mapOf("id" to id)) {
            it.map { it.toVertex().toObjectPropertyValueVertex() }.toSet()
        }
    }

    fun valuesOfProperty(id: String): Set<ObjectPropertyValueVertex> = valuesOfProperty(ORecordId(id))

    private fun valuesOfProperty(id: ORID): Set<ObjectPropertyValueVertex> = transaction(db) {
        db.query("select expand(in(\"$OBJECT_VALUE_OBJECT_PROPERTY_EDGE\")) from :id", mapOf("id" to id)) {
            it.map { it.toVertex().toObjectPropertyValueVertex() }.toSet()
        }
    }

    fun valuesOfProperties(ids: List<ORID>): Set<ObjectPropertyValueVertex> = transaction(db) {
        db.query("select expand(in(\"$OBJECT_VALUE_OBJECT_PROPERTY_EDGE\")) from :ids", mapOf("ids" to ids)) {
            it.map { it.toVertex().toObjectPropertyValueVertex() }.toSet()
        }
    }

    fun propertiesOfObject(id: String): Set<ObjectPropertyVertex> = propertiesOfObject(ORecordId(id))

    private fun propertiesOfObject(id: ORID): Set<ObjectPropertyVertex> = transaction(db) {
        db.query("select expand(in(\"$OBJECT_OBJECT_PROPERTY_EDGE\")) from :id", mapOf("id" to id)) {
            it.map { it.toVertex().toObjectPropertyVertex() }.toSet()
        }
    }

    fun linkedFrom(id: Set<ORID>, linkTypes: Set<String>, except: Set<ORID> = emptySet()): Map<ORID, Set<ORID>> {
        val linkClasses = linkTypes.joinToString("\", \"", "\"", "\"")

        return db.query("select @rid as sourceId,  in($linkClasses).@rid as id from :id", mapOf("id" to id)) {
            it.map {
                it.getProperty<ORID>("sourceId") to it.getProperty<List<ORID>>("id").toSet().filterNot { except.contains(it) }.toSet()
            }.toMap().filterValues { it.isNotEmpty() }
        }
    }

    fun valuesBetween(sources: Set<ORID>, targets: Set<ORID>): Set<ObjectPropertyValueVertex> {
        return db.query(
            "SELECT FROM (traverse out(\"$OBJECT_VALUE_OBJECT_VALUE_EDGE\") from :sources while not  @rid in :targets)",
            mapOf("sources" to sources, "targets" to targets)
        ) {
            val res = it.toList()
            res.map { it.toVertex().toObjectPropertyValueVertex() }.toSet()
        }
    }

    fun getPropertyValues(propertyVertex: ObjectPropertyVertex): List<DetailedRootValueViewResponse> =
        transaction(db) {
            val rootPropertyValues = propertyVertex.values.filter { it.aspectProperty == null }
            return@transaction rootPropertyValues.map { rootValue ->
                DetailedRootValueViewResponse(
                    rootValue.id,
                    rootValue.toObjectPropertyValue().calculateObjectValueData().toDTO(),
                    rootValue.guid,
                    rootValue.getOrCalculateMeasureSymbol(),
                    rootValue.description,
                    rootValue.children.map { it.toDetailedAspectPropertyValueResponse() }
                )
            }
        }

    private fun ObjectPropertyValueVertex.toDetailedAspectPropertyValueResponse(): DetailedValueViewResponse {
        val aspectProperty = this.aspectProperty ?: throw IllegalStateException("Object property with id ${this.id} has no associated aspect")
        val aspect = aspectProperty.associatedAspect
        return DetailedValueViewResponse(
            this.id,
            this.toObjectPropertyValue().calculateObjectValueData().toDTO(),
            this.guid,
            this.getOrCalculateMeasureSymbol(),
            this.description,
            AspectPropertyDataExtended(
                aspectProperty.name,
                aspect.name,
                aspect.baseType ?: throw IllegalStateException("Aspect with id ${aspect.id} has no associated base type"),
                aspect.subject?.name,
                aspect.referenceBookRootVertex?.name
            ),
            this.children.map { it.toDetailedAspectPropertyValueResponse() }
        )
    }

    fun saveObject(vertex: ObjectVertex, info: ObjectWriteInfo, properties: List<ObjectPropertyVertex>): ObjectVertex =
        transaction(db) {
            vertex.name = info.name
            vertex.description = info.description

            if (vertex.subject != info.subject) {
                vertex.addEdge(info.subject, OBJECT_SUBJECT_EDGE).save<OEdge>()
            }

            val newProperties: Set<ObjectPropertyVertex> = properties.toSet()
            val currentProperties: Set<ObjectPropertyVertex> = vertex.properties.toSet()
            val toDelete: Set<ObjectPropertyVertex> = currentProperties.minus(newProperties)
            val toAdd = newProperties.minus(currentProperties)

            toDelete.forEach { it.delete<ObjectPropertyVertex>() }
            toAdd.forEach {
                it.addEdge(vertex, OBJECT_OBJECT_PROPERTY_EDGE).save<OEdge>()
            }

            return@transaction vertex.save<OVertex>().toObjectVertex()
        }

    fun updateObject(vertex: ObjectVertex, info: ObjectWriteInfo): ObjectVertex =
        transaction(db) {
            vertex.name = info.name
            vertex.description = info.description
            if (vertex.subject?.id != info.subject.id) {
                vertex.getEdges(ODirection.OUT, OBJECT_SUBJECT_EDGE).forEach { it.delete<OEdge>() }
                vertex.addEdge(info.subject, OBJECT_SUBJECT_EDGE).save<OEdge>()
            }
            return@transaction vertex.save<OVertex>().toObjectVertex()
        }

    fun saveObjectProperty(
        vertex: ObjectPropertyVertex,
        info: PropertyWriteInfo,
        values: List<ObjectPropertyValueVertex>
    ): ObjectPropertyVertex =
        transaction(db) {
            vertex.name = info.name
            vertex.description = info.description

            replaceEdge(vertex, OBJECT_OBJECT_PROPERTY_EDGE, vertex.objekt, info.objekt)
            replaceEdge(vertex, ASPECT_OBJECT_PROPERTY_EDGE, vertex.aspect, info.aspect)

            var valuesMap = values.associateBy { it.id }
            var toDelete = emptyMap<String, ObjectPropertyValueVertex>()
            vertex.values.forEach {
                if (valuesMap.containsKey(it.id)) {
                    valuesMap -= it.id
                } else {
                    toDelete += it.id to it
                }
            }

            toDelete.forEach { _, value -> value.delete<ObjectPropertyValueVertex>() }
            valuesMap.forEach { _, value ->
                value.addEdge(vertex, OBJECT_VALUE_OBJECT_PROPERTY_EDGE).save<OEdge>()
            }

            return@transaction vertex.save<OVertex>().toObjectPropertyVertex()
        }

    fun saveObjectValue(
        vertex: ObjectPropertyValueVertex,
        valueInfo: ValueWriteInfo
    ): ObjectPropertyValueVertex = transaction(db) {
        logger.debug("saving object value by info $valueInfo")

        vertex.description = valueInfo.description
        val newTypeTag = valueInfo.value.tag()

        if (vertex.typeTag != newTypeTag) {
            when (vertex.typeTag) {
                ScalarTypeTag.INTEGER -> {
                    vertex.removeProperty<Int>(INT_TYPE_PROPERTY)
                    vertex.removeProperty<Int>(INT_TYPE_UPB_PROPERTY)
                    vertex.removeProperty<Int>(PRECISION_PROPERTY)
                }
                ScalarTypeTag.DECIMAL -> {
                    vertex.removeProperty<BigDecimal>(DECIMAL_TYPE_PROPERTY)
                    vertex.removeProperty<Int>(DECIMAL_TYPE_UPB_PROPERTY)
                }
                ScalarTypeTag.STRING -> vertex.removeProperty<String>(STR_TYPE_PROPERTY)
                ScalarTypeTag.RANGE -> vertex.removeProperty<Range>(RANGE_TYPE_PROPERTY)
                ScalarTypeTag.BOOLEAN -> vertex.removeProperty<Boolean>(BOOL_TYPE_PROPERTY)
                ScalarTypeTag.SUBJECT -> {
                    vertex.getEdges(ODirection.OUT, OBJECT_VALUE_SUBJECT_EDGE).forEach { it.delete<OEdge>() }
                }
                ScalarTypeTag.OBJECT -> {
                    vertex.getEdges(ODirection.OUT, OBJECT_VALUE_OBJECT_EDGE).forEach { it.delete<OEdge>() }
                }
                ScalarTypeTag.OBJECT_PROPERTY -> {
                    vertex.getEdges(ODirection.OUT, OBJECT_VALUE_REF_OBJECT_PROPERTY_EDGE).forEach { it.delete<OEdge>() }
                }
                ScalarTypeTag.OBJECT_VALUE -> {
                    vertex.getEdges(ODirection.OUT, OBJECT_VALUE_REF_OBJECT_VALUE_EDGE).forEach { it.delete<OEdge>() }
                }
                ScalarTypeTag.ASPECT -> {
                    vertex.getEdges(ODirection.OUT, OBJECT_VALUE_ASPECT_EDGE).forEach { it.delete<OEdge>() }
                }
                ScalarTypeTag.ASPECT_PROPERTY -> {
                    vertex.getEdges(ODirection.OUT, OBJECT_VALUE_ASPECT_PROPERTY_EDGE).forEach { it.delete<OEdge>() }
                }
                ScalarTypeTag.DOMAIN_ELEMENT -> {
                    vertex.getEdges(ODirection.OUT, OBJECT_VALUE_DOMAIN_ELEMENT_EDGE).forEach { it.delete<OEdge>() }
                }
                ScalarTypeTag.REF_BOOK_ITEM -> {
                    vertex.getEdges(ODirection.OUT, OBJECT_VALUE_REF_REFBOOK_ITEM_EDGE).forEach { it.delete<OEdge>() }
                }
                ScalarTypeTag.NULL -> {
                }
            }

            vertex.typeTag = newTypeTag
        }

        val objectValue = valueInfo.value
        when (objectValue) {
            is ObjectValue.IntegerValue -> {
                vertex.intValue = objectValue.value
                vertex.intUpb = if (objectValue.upb != objectValue.value) objectValue.upb else null
                vertex.precision = objectValue.precision
            }
            is ObjectValue.DecimalValue -> {
                logger.info("store decimal value: $objectValue")
                if (RangeFlagConstants.RANGE.isSet(objectValue.rangeFlags)) {
                    // is range
                    vertex.decimalValue = objectValue.value
                    vertex.decimalUpb = objectValue.upb

                    val leftFlag = RangeFlagConstants.LEFT_INF.bitmask
                    val rightFlag = RangeFlagConstants.RIGHT_INF.bitmask

                    vertex.leftInfinity = objectValue.rangeFlags.and(leftFlag) != 0
                    vertex.rightInfinity = objectValue.rangeFlags.and(rightFlag) != 0
                } else {
                    // is ordinary decimal
                    vertex.decimalValue = objectValue.value
                    vertex.decimalUpb = objectValue.value
                    vertex.leftInfinity = false
                    vertex.rightInfinity = false
                }
            }
            is ObjectValue.StringValue -> {
                vertex.strValue = objectValue.value
            }
            is ObjectValue.RangeValue -> {
                vertex.range = objectValue.range
            }
            is ObjectValue.BooleanValue -> {
                vertex.booleanValue = objectValue.value
            }

            is ObjectValue.Link -> {
                val linkValue = objectValue.value
                when (linkValue) {
                    is LinkValueVertex.Object -> replaceEdge(vertex, OBJECT_VALUE_OBJECT_EDGE, vertex.refValueObject, linkValue.vertex)
                    is LinkValueVertex.ObjectProperty -> replaceEdge(
                        vertex,
                        OBJECT_VALUE_REF_OBJECT_PROPERTY_EDGE,
                        vertex.refValueObjectProperty,
                        linkValue.vertex
                    )
                    is LinkValueVertex.ObjectValue -> replaceEdge(vertex, OBJECT_VALUE_REF_OBJECT_VALUE_EDGE, vertex.refValueObjectValue, linkValue.vertex)
                    is LinkValueVertex.Subject -> replaceEdge(vertex, OBJECT_VALUE_SUBJECT_EDGE, vertex.refValueSubject, linkValue.vertex)
                    is LinkValueVertex.DomainElement -> replaceEdge(vertex, OBJECT_VALUE_DOMAIN_ELEMENT_EDGE, vertex.refValueDomainElement, linkValue.vertex)
                    is LinkValueVertex.Aspect -> replaceEdge(vertex, OBJECT_VALUE_ASPECT_EDGE, vertex.refValueAspect, linkValue.vertex)
                    is LinkValueVertex.AspectProperty -> replaceEdge(
                        vertex,
                        OBJECT_VALUE_REF_ASPECT_PROPERTY_EDGE,
                        vertex.refValueAspectProperty,
                        linkValue.vertex
                    )
                }
            }
        }

        replaceEdge(vertex, OBJECT_VALUE_MEASURE_EDGE, vertex.measure, valueInfo.measure)
        replaceEdge(vertex, OBJECT_VALUE_OBJECT_PROPERTY_EDGE, vertex.objectProperty, valueInfo.objectProperty)
        replaceEdge(vertex, OBJECT_VALUE_ASPECT_PROPERTY_EDGE, vertex.aspectProperty, valueInfo.aspectProperty)
        replaceEdge(vertex, OBJECT_VALUE_OBJECT_VALUE_EDGE, vertex.parentValue, valueInfo.parentValue)

        return@transaction vertex.save<OVertex>().toObjectPropertyValueVertex()
    }


    fun deleteAll(vertices: List<OVertex>) {
        transaction(db) {
            vertices.forEach { db.delete(it) }
        }
    }

    fun delete(vertex: OVertex) = deleteAll(listOf(vertex))


    fun delete(deleteInfo: DeleteInfo) {
        transaction(db) {
            deleteInfo.incoming.forEach { db.delete(it) }
            deleteInfo.outgoing.forEach { db.delete(it) }
            db.delete(deleteInfo.vertex)
        }
    }

    fun softDelete(vertex: DeletableVertex) {
        transaction(db) {
            vertex.deleted = true
        }
    }

    fun getObjectVertex(id: String) = transaction(db) { db.getVertexById(id)?.toObjectVertex() }
    fun getObjectPropertyVertex(id: String) = transaction(db) { db.getVertexById(id)?.toObjectPropertyVertex() }
    fun getObjectPropertyValueVertex(id: String) = transaction(db) { db.getVertexById(id)?.toObjectPropertyValueVertex() }

    fun getObjectVertexesByNameAndSubject(name: String, subjectId: ORID): List<ObjectVertex> {
        val sqlBuilder = objectSqlBuilder {
            allObjects {
                withoutDeleted()
                withName(name)
                withSubjectLink(subjectId)
            }
        }
        return db.query(sqlBuilder.sql, sqlBuilder.params) { rs ->
            rs.map { it.toVertex().toObjectVertex() }.toList()
        }
    }

    fun getPropertyVertexesByNameAndAspect(name: String?, objectId: ORID, aspectId: ORID): List<ObjectPropertyVertex> {

        val sqlBuilder = objectSqlBuilder {
            fromObject(objectId) {
                toObjProps {
                    withName(name)
                    withoutDeleted()
                    withAspect(aspectId)
                }
            }
        }

        return db.query(sqlBuilder.sql, sqlBuilder.params) { rs ->
            rs.map { it.toVertex().toObjectPropertyVertex() }.toList()
        }
    }

    fun baseType(propertyVertex: ObjectPropertyVertex): String? = transaction(db) {
        propertyVertex.aspect?.baseType
    }

}

private val logger = loggerFor<ObjectDaoService>()

sealed class ObjectException(message: String) : Exception(message)
class EmptyObjectCreateNameException(data: ObjectCreateRequest) : ObjectException("object name is empty: $data")
class EmptyObjectUpdateNameException(data: ObjectUpdateRequest) : ObjectException("object name is empty: $data")
class ObjectNotFoundException(id: String) : ObjectException("object not found. id: $id")
class ObjectAlreadyExists(name: String) : ObjectException("object with name $name already exists")
class ObjectPropertyNotFoundException(id: String) : ObjectException("object property not found. id: $id")
class ObjectPropertyAlreadyExistException(name: String?, objectId: String, aspectId: String) :
    ObjectException("object property with name $name and aspect $aspectId already exists in object $objectId")

class ObjectConcurrentEditException(id: String, name: String, subjectName: String?) :
    ObjectException("Object $name ($subjectName) with id = $id is already modified")

class ObjectPropertyConcurrentEditException(id: String, name: String?) : ObjectException("Object property $name with id = $id is already modified")
class ObjectPropertyValueConcurrentModificationException(id: String) : ObjectException("Object property value with id = $id is already modified")

class ObjectPropertyValueNotFoundException(id: String) : ObjectException("object property value not found. id: $id")
class ObjectWithoutSubjectException(id: String) : ObjectException("Object vertex $id has no subject")
class ObjectIsLinkedException(valueIds: List<String>, propertyIds: List<String>, objectId: String?) :
    ObjectException("linked values: $valueIds, linked properties: $propertyIds, inked object: $objectId")

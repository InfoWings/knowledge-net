package com.infowings.catalog.data.objekt

import com.infowings.catalog.MasterCatalog
import com.infowings.catalog.common.*
import com.infowings.catalog.common.objekt.ObjectCreateRequest
import com.infowings.catalog.common.objekt.PropertyCreateRequest
import com.infowings.catalog.common.objekt.ValueCreateRequest
import com.infowings.catalog.data.MeasureService
import com.infowings.catalog.data.Subject
import com.infowings.catalog.data.SubjectService
import com.infowings.catalog.data.aspect.Aspect
import com.infowings.catalog.data.aspect.AspectService
import com.infowings.catalog.data.history.HistoryFact
import com.infowings.catalog.data.history.HistoryService
import com.infowings.catalog.data.history.asString
import com.infowings.catalog.data.reference.book.ReferenceBookService
import com.infowings.catalog.storage.*
import com.orientechnologies.orient.core.id.ORID
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner
import kotlin.test.assertEquals
import kotlin.test.fail

@RunWith(SpringJUnit4ClassRunner::class)
@SpringBootTest(classes = [MasterCatalog::class])
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class ObjectHistoryTest {
    @Autowired
    private lateinit var db: OrientDatabase
    @Autowired
    private lateinit var dao: ObjectDaoService
    @Autowired
    private lateinit var subjectService: SubjectService
    @Autowired
    private lateinit var aspectService: AspectService
    @Autowired
    private lateinit var measureService: MeasureService
    @Autowired
    private lateinit var objectService: ObjectService
    @Autowired
    private lateinit var refBookService: ReferenceBookService
    @Autowired
    private lateinit var historyService: HistoryService

    private lateinit var subject: Subject

    private lateinit var aspect: Aspect

    private lateinit var complexAspect: Aspect

    private val username = "admin"

    @Before
    fun initTestData() {
        subject = subjectService.createSubject(SubjectData(name = "subjectName", description = "descr"), username)
        aspect = aspectService.save(
            AspectData(name = "aspectName", description = "aspectDescr", baseType = BaseType.Text.name), username
        )
        val property = AspectPropertyData("", "p", aspect.id, PropertyCardinality.INFINITY.name, null)
        val complexAspectData = AspectData(
            "",
            "complex",
            Kilometre.name,
            null,
            BaseType.Decimal.name,
            listOf(property)
        )
        complexAspect = aspectService.save(complexAspectData, username)
    }

    @Test
    fun createObjectHistoryTest() {
        val testName = "createObjectHistoryTest"

        val eventsBefore: Set<HistoryFact> = historyService.getAll().toSet()
        val objectEventsBefore = objectEvents(eventsBefore)
        val subjectEventsBefore = subjectEvents(eventsBefore)

        val factsBefore: Set<HistoryFact> = historyService.getAll().toSet()
        val objectFactsBefore = objectEvents(factsBefore)
        val subjectFactsBefore = subjectEvents(factsBefore)

        val request = ObjectCreateRequest(testName, "object descr", subject.id, subject.version)
        val created = objectService.create(request, "user")

        val factsAfter = historyService.getAll().toSet()
        val objectFactsAfter = objectEvents(factsAfter)
        val subjectFactsAfter = subjectEvents(factsAfter)

        val objectFactsAdded = objectFactsAfter - objectFactsBefore
        val subjectFactsAdded = subjectFactsAfter - subjectFactsBefore

        assertEquals(1, objectFactsAdded.size, "exactly one object fact must appear")
        val objectEvent = objectFactsAdded.firstOrNull()?.event
        assertEquals(OBJECT_CLASS, objectEvent?.entityClass, "class must be correct")
        assertEquals(EventType.CREATE, objectEvent?.type, "event type must be correct")

        assertEquals(1, subjectFactsAdded.size, "exactly one subject event must appear")
        val subjectEvent = subjectFactsAdded.firstOrNull()?.event
        assertEquals(SUBJECT_CLASS, subjectEvent?.entityClass, "class must be correct")
        assertEquals(EventType.UPDATE, subjectEvent?.type, "event type must be correct")
    }

    @Test
    fun createPropertyHistoryTest() {
        val testName = "createPropertyHistoryTest"

        val createdObjectId = createObject(testName)

        val factsBefore: Set<HistoryFact> = historyService.getAll().toSet()
        val objectFactsBefore = objectEvents(factsBefore)
        val propertyFactsBefore = propertyEvents(factsBefore)

        val propertyRequest = PropertyCreateRequest(
            objectId = createdObjectId,
            name = "prop_$testName", cardinality = PropertyCardinality.INFINITY.name, aspectId = aspect.id
        )
        val createdPropertyId = objectService.create(propertyRequest, "user")

        val factsAfter: Set<HistoryFact> = historyService.getAll().toSet()
        val objectFactsAfter = objectEvents(factsAfter)
        val propertyFactsAfter = propertyEvents(factsAfter)


        val objectFactsAdded = objectFactsAfter - objectFactsBefore
        val propertyFactsAdded = propertyFactsAfter - propertyFactsBefore

        assertEquals(1, propertyFactsAdded.size, "exactly one object property fact must appear")
        val propertyEvent = propertyFactsAdded.first().event
        val propertyPayload = propertyFactsAdded.first().payload
        assertEquals(OBJECT_PROPERTY_CLASS, propertyEvent.entityClass, "class must be correct")
        assertEquals(EventType.CREATE, propertyEvent.type, "event type must be correct")

        assertEquals(setOf("name", "cardinality"), propertyPayload.data.keys, "data keys must be correct")
        assertEquals(emptySet(), propertyPayload.removedLinks.keys, "there must be no removed links")
        assertEquals(setOf("aspect", "object"), propertyPayload.addedLinks.keys, "added links keys must be correct")

        assertEquals(propertyRequest.name, propertyPayload.data["name"], "name must be correct")
        assertEquals(propertyRequest.cardinality, propertyPayload.data["cardinality"], "cardinality must be correct")

        val aspectLinks = propertyPayload.addedLinks["aspect"] ?: fail("unexpected absence of aspect links")
        assertEquals(1, aspectLinks.size, "only 1 aspect must be here")
        assertEquals(aspect.id, aspectLinks.first().toString(), "aspect id must be correct")

        val objectLinks = propertyPayload.addedLinks["object"] ?: fail("unexpected absence of aspect links")
        assertEquals(1, objectLinks.size, "only 1 object must be here")
        assertEquals(createdObjectId, objectLinks.first().toString(), "object id must be correct")

        assertEquals(1, objectFactsAdded.size, "exactly one object event must appear")
        val objectEvent = objectFactsAdded.first().event
        val objectPayload = objectFactsAdded.first().payload
        assertEquals(OBJECT_CLASS, objectEvent.entityClass, "class must be correct")
        assertEquals(EventType.UPDATE, objectEvent.type, "event type must be correct")

        assertEquals(emptySet(), objectPayload.data.keys, "data keys must be empty")
        assertEquals(emptySet(), objectPayload.removedLinks.keys, "there must be no removed links")
        assertEquals(setOf("properties"), objectPayload.addedLinks.keys, "added links keys must be correct")

        val propertiesLinks = objectPayload.addedLinks["properties"] ?: fail("unexpected absence of properties links")
        assertEquals(1, propertiesLinks.size, "only 1 property must be here")
        assertEquals(createdPropertyId, propertiesLinks.first().toString(), "property id must be correct")
    }

    private data class PreparedValueInfo(
        val value: ObjectPropertyValue, val propertyId: String,
        val propertyFacts: List<HistoryFact>, val valueFacts: List<HistoryFact>
    )

    private fun prepareValue(
        testName: String,
        value: ObjectValueData,
        aspectPropertyId: String? = null,
        measureId: String? = null
    ): PreparedValueInfo {
        val createdObjectId = createObject(testName)

        val propertyRequest = PropertyCreateRequest(
            objectId = createdObjectId,
            name = "prop_$testName", cardinality = PropertyCardinality.INFINITY.name, aspectId = aspect.id
        )
        val createdPropertyId = objectService.create(propertyRequest, "user")

        val factsBefore: Set<HistoryFact> = historyService.getAll().toSet()

        val valueRequest = ValueCreateRequest(
            value = value, objectPropertyId = createdPropertyId,
            aspectPropertyId = aspectPropertyId, measureId = measureId, parentValueId = null
        )
        val propertyFactsBefore = propertyEvents(factsBefore)
        val valueFactsBefore = valueEvents(factsBefore)

        val createdValue = objectService.create(valueRequest, "user")

        val factsAfter: Set<HistoryFact> = historyService.getAll().toSet()
        val propertyFactsAfter = propertyEvents(factsAfter)
        val valueFactsAfter = valueEvents(factsAfter)

        val propertyFactsAdded = propertyFactsAfter - propertyFactsBefore
        val valueFactsAdded = valueFactsAfter - valueFactsBefore

        return PreparedValueInfo(createdValue, createdPropertyId, propertyFactsAdded, valueFactsAdded)
    }

    private fun prepareAnotherValue(prepared: PreparedValueInfo, value: ObjectValueData): PreparedValueInfo {
        val factsBefore: Set<HistoryFact> = historyService.getAll().toSet()

        val valueRequest = ValueCreateRequest(value = value, objectPropertyId = prepared.propertyId)
        val propertyFactsBefore = propertyEvents(factsBefore)
        val valueFactsBefore = valueEvents(factsBefore)

        val createdValue = objectService.create(valueRequest, "user")

        val factsAfter: Set<HistoryFact> = historyService.getAll().toSet()
        val propertyFactsAfter = propertyEvents(factsAfter)
        val valueFactsAfter = valueEvents(factsAfter)

        val propertyFactsAdded = propertyFactsAfter - propertyFactsBefore
        val valueFactsAdded = valueFactsAfter - valueFactsBefore

        return PreparedValueInfo(createdValue, prepared.propertyId, propertyFactsAdded, valueFactsAdded)
    }

    private fun prepareChildValue(prepared: PreparedValueInfo, value: ObjectValueData): PreparedValueInfo {
        val factsBefore: Set<HistoryFact> = historyService.getAll().toSet()

        val valueRequest = ValueCreateRequest(
            value = value, objectPropertyId = prepared.propertyId,
            parentValueId = prepared.value.id?.toString(), aspectPropertyId = null, measureId = null
        )

        val propertyFactsBefore = propertyEvents(factsBefore)
        val valueFactsBefore = valueEvents(factsBefore)

        val createdValue = objectService.create(valueRequest, "user")

        val factsAfter: Set<HistoryFact> = historyService.getAll().toSet()

        val propertyFactsAfter = propertyEvents(factsAfter)
        val valueFactsAfter = valueEvents(factsAfter)

        val propertyFactsAdded = propertyFactsAfter - propertyFactsBefore
        val valueFactsAdded = valueFactsAfter - valueFactsBefore

        return PreparedValueInfo(createdValue, prepared.propertyId, propertyFactsAdded, valueFactsAdded)
    }

    private fun checkPropertyFacts(propertyFacts: List<HistoryFact>, propertyId: String, valueId: ORID) {
        assertEquals(1, propertyFacts.size, "one property event is expected")
        val propertyEvent = propertyFacts.first().event
        val propertyPayload = propertyFacts.first().payload
        assertEquals(propertyId, propertyEvent.entityId.toString(), "id must be correct")
        assertEquals(EventType.UPDATE, propertyEvent.type, "type must be correct")
        assertEquals(emptySet(), propertyPayload.data.keys, "there must be no data keys")
        assertEquals(emptySet(), propertyPayload.removedLinks.keys, "there must be no removed links")
        assertEquals(setOf("values"), propertyPayload.addedLinks.keys, "added links keys must be correct")
        val valueLinks = propertyPayload.addedLinks["values"]
        if (valueLinks != null) {
            assertEquals(listOf(valueId), valueLinks, "value id must be correct")
        } else {
            fail("value links must present")
        }
    }

    private fun checkPropertyFacts(prepared: PreparedValueInfo) {
        val valueId = prepared.value.id
        if (valueId != null) {
            checkPropertyFacts(prepared.propertyFacts, prepared.propertyId, valueId)
        } else {
            fail("value id is null")
        }
    }

    @Test
    fun createValueNullHistoryTest() {
        val testName = "createValueNullHistoryTest"

        val prepared = prepareValue(testName, ObjectValueData.NullValue)
        val valueFacts = prepared.valueFacts

        assertEquals(1, valueFacts.size, "exactly one object property event must appear")
        val valueEvent = valueFacts.first().event
        val valuePayload = valueFacts.first().payload
        assertEquals(OBJECT_PROPERTY_VALUE_CLASS, valueEvent.entityClass, "class must be correct")
        assertEquals(EventType.CREATE, valueEvent.type, "event type must be correct")

        assertEquals(setOf("typeTag"), valuePayload.data.keys, "data keys must be correct")
        assertEquals(emptySet(), valuePayload.removedLinks.keys, "there must be no removed links")
        assertEquals(setOf("objectProperty"), valuePayload.addedLinks.keys, "added links keys must be correct")

        assertEquals(ScalarTypeTag.NULL.name, valuePayload.data["typeTag"], "type tag must be correct")

        val propertyLinks = valuePayload.addedLinks["objectProperty"] ?: fail("unexpected absence of property links")
        assertEquals(1, propertyLinks.size, "only 1 property must be here")
        assertEquals(prepared.propertyId, propertyLinks.first().toString(), "property id must be correct")

        checkPropertyFacts(prepared)
    }

    @Test
    fun createValueStrHistoryTest() {
        val testName = "createValueStrHistoryTest"
        val value = "hello"

        val prepared = prepareValue(testName, ObjectValueData.StringValue(value))
        val valueFacts = prepared.valueFacts

        assertEquals(1, valueFacts.size, "exactly one object property event must appear")
        val valueEvent = valueFacts.first().event
        val valuePayload = valueFacts.first().payload
        assertEquals(OBJECT_PROPERTY_VALUE_CLASS, valueEvent.entityClass, "class must be correct")
        assertEquals(EventType.CREATE, valueEvent.type, "event type must be correct")

        assertEquals(setOf("typeTag", "strValue"), valuePayload.data.keys, "data keys must be correct")
        assertEquals(emptySet(), valuePayload.removedLinks.keys, "there must be no removed links")
        assertEquals(setOf("objectProperty"), valuePayload.addedLinks.keys, "added links keys must be correct")

        assertEquals(ScalarTypeTag.STRING.name, valuePayload.data["typeTag"], "type tag must be correct")
        assertEquals(value, valuePayload.data["strValue"], "value must be correct")

        val propertyLinks = valuePayload.addedLinks["objectProperty"] ?: fail("unexpected absence of property links")
        assertEquals(1, propertyLinks.size, "only 1 property must be here")
        assertEquals(prepared.propertyId, propertyLinks.first().toString(), "property id must be correct")

        checkPropertyFacts(prepared)
    }

    @Test
    fun createValueDecimalHistoryTest() {
        val testName = "createValueDecimalHistoryTest"
        val value = "123.12"

        val prepared = prepareValue(testName, ObjectValueData.DecimalValue(value))
        val valueFacts = prepared.valueFacts

        assertEquals(1, valueFacts.size, "exactly one object property event must appear")
        val valueEvent = valueFacts.first().event
        val valuePayload = valueFacts.first().payload
        assertEquals(OBJECT_PROPERTY_VALUE_CLASS, valueEvent.entityClass, "class must be correct")
        assertEquals(EventType.CREATE, valueEvent.type, "event type must be correct")

        assertEquals(setOf("typeTag", "decimalValue"), valuePayload.data.keys, "data keys must be correct")
        assertEquals(emptySet(), valuePayload.removedLinks.keys, "there must be no removed links")
        assertEquals(setOf("objectProperty"), valuePayload.addedLinks.keys, "added links keys must be correct")

        assertEquals(ScalarTypeTag.DECIMAL.name, valuePayload.data["typeTag"], "type tag must be correct")
        assertEquals(value, valuePayload.data["decimalValue"], "value must be correct")

        val propertyLinks = valuePayload.addedLinks["objectProperty"] ?: fail("unexpected absence of property links")
        assertEquals(1, propertyLinks.size, "only 1 property must be here")
        assertEquals(prepared.propertyId, propertyLinks.first().toString(), "property id must be correct")

        checkPropertyFacts(prepared)
    }

    @Test
    fun createValueRangeHistoryTest() {
        val testName = "createValueRangeHistoryTest"
        val range = Range(3, 5)

        val prepared = prepareValue(testName, ObjectValueData.RangeValue(range))
        val valueFacts = prepared.valueFacts

        assertEquals(1, valueFacts.size, "exactly one object property event must appear")
        val valueEvent = valueFacts.first().event
        val valuePayload = valueFacts.first().payload
        assertEquals(OBJECT_PROPERTY_VALUE_CLASS, valueEvent.entityClass, "class must be correct")
        assertEquals(EventType.CREATE, valueEvent.type, "event type must be correct")

        assertEquals(setOf("typeTag", "range"), valuePayload.data.keys, "data keys must be correct")
        assertEquals(emptySet(), valuePayload.removedLinks.keys, "there must be no removed links")
        assertEquals(setOf("objectProperty"), valuePayload.addedLinks.keys, "added links keys must be correct")

        assertEquals(ScalarTypeTag.RANGE.name, valuePayload.data["typeTag"], "type tag must be correct")
        assertEquals(range.asString(), valuePayload.data["range"], "value must be correct")

        val propertyLinks = valuePayload.addedLinks["objectProperty"] ?: fail("unexpected absence of property links")
        assertEquals(1, propertyLinks.size, "only 1 property must be here")
        assertEquals(prepared.propertyId, propertyLinks.first().toString(), "property id must be correct")

        checkPropertyFacts(prepared)
    }

    @Test
    fun createValueIntHistoryTest() {
        val testName = "createValueIntHistoryTest"
        val intValue = 234

        val prepared = prepareValue(testName, ObjectValueData.IntegerValue(intValue, null))
        val valueFacts = prepared.valueFacts

        assertEquals(1, valueFacts.size, "exactly one object property event must appear")
        val valueEvent = valueFacts.first().event
        val valuePayload = valueFacts.first().payload
        assertEquals(OBJECT_PROPERTY_VALUE_CLASS, valueEvent.entityClass, "class must be correct")
        assertEquals(EventType.CREATE, valueEvent.type, "event type must be correct")

        assertEquals(setOf("typeTag", "intValue"), valuePayload.data.keys, "data keys must be correct")
        assertEquals(emptySet(), valuePayload.removedLinks.keys, "there must be no removed links")
        assertEquals(setOf("objectProperty"), valuePayload.addedLinks.keys, "added links keys must be correct")

        assertEquals(ScalarTypeTag.INTEGER.name, valuePayload.data["typeTag"], "type tag must be correct")
        assertEquals(intValue.toString(), valuePayload.data["intValue"], "value must be correct")

        val propertyLinks = valuePayload.addedLinks["objectProperty"] ?: fail("unexpected absence of property links")
        assertEquals(1, propertyLinks.size, "only 1 property must be here")
        assertEquals(prepared.propertyId, propertyLinks.first().toString(), "property id must be correct")

        checkPropertyFacts(prepared)
    }


    @Test
    fun createValueIntPrecHistoryTest() {
        val testName = "createValueIntPrecHistoryTest"
        val intValue = 234
        val precision = 2

        val prepared = prepareValue(testName, ObjectValueData.IntegerValue(intValue, precision))
        val valueFacts = prepared.valueFacts

        assertEquals(1, valueFacts.size, "exactly one object property event must appear")
        val valueEvent = valueFacts.first().event
        val valuePayload = valueFacts.first().payload
        assertEquals(OBJECT_PROPERTY_VALUE_CLASS, valueEvent.entityClass, "class must be correct")
        assertEquals(EventType.CREATE, valueEvent.type, "event type must be correct")

        assertEquals(setOf("typeTag", "intValue", "precision"), valuePayload.data.keys, "data keys must be correct")
        assertEquals(emptySet(), valuePayload.removedLinks.keys, "there must be no removed links")
        assertEquals(setOf("objectProperty"), valuePayload.addedLinks.keys, "added links keys must be correct")

        assertEquals(ScalarTypeTag.INTEGER.name, valuePayload.data["typeTag"], "type tag must be correct")
        assertEquals(intValue, valuePayload.data["intValue"]?.toInt(), "value must be correct")
        assertEquals(precision, valuePayload.data["precision"]?.toInt(), "precision must be correct")

        val propertyLinks = valuePayload.addedLinks["objectProperty"] ?: fail("unexpected absence of property links")
        assertEquals(1, propertyLinks.size, "only 1 property must be here")
        assertEquals(prepared.propertyId, propertyLinks.first().toString(), "property id must be correct")

        checkPropertyFacts(prepared)
    }

    @Test
    fun createAnotherValueHistoryTest() {
        val testName = "createAnotherValueHistoryTest"
        val value1 = "hello"
        val value2 = "world"

        val prepared1 = prepareValue(testName, ObjectValueData.StringValue(value1))
        val prepared2 = prepareAnotherValue(prepared1, ObjectValueData.StringValue(value2))

        val valueFacts = prepared2.valueFacts

        assertEquals(1, valueFacts.size, "exactly two value facts must appear")

        val childEvent = valueFacts.first().event
        val childPayload = valueFacts.first().payload
        assertEquals(OBJECT_PROPERTY_VALUE_CLASS, childEvent.entityClass, "class must be correct")
        assertEquals(EventType.CREATE, childEvent.type, "event type must be correct")

        assertEquals(setOf("typeTag", "strValue"), childPayload.data.keys, "data keys must be correct")
        assertEquals(emptySet(), childPayload.removedLinks.keys, "there must be no removed links")
        assertEquals(setOf("objectProperty"), childPayload.addedLinks.keys, "added links keys must be correct")

        assertEquals(ScalarTypeTag.STRING.name, childPayload.data["typeTag"], "type tag must be correct")
        assertEquals(value2, childPayload.data["strValue"], "value must be correct")

        val propertyLinks = childPayload.addedLinks["objectProperty"] ?: fail("unexpected absence of property links")
        assertEquals(1, propertyLinks.size, "only 1 property must be here")
        assertEquals(prepared2.propertyId, propertyLinks.first().toString(), "property id must be correct")

        checkPropertyFacts(prepared2)
    }


    @Test
    fun createChildValueHistoryTest() {
        val testName = "createChildValuePrecHistoryTest"
        val value1 = "hello"
        val value2 = "world"

        val prepared1 = prepareValue(testName, ObjectValueData.StringValue(value1))
        val prepared2 = prepareChildValue(prepared1, ObjectValueData.StringValue(value2))

        val valueFacts = prepared2.valueFacts

        assertEquals(2, valueFacts.size, "exactly two value facts must appear")

        val byEntity = valueFacts.groupBy { it.event.entityId }

        assertEquals(
            setOf(prepared1.value.id.toString(), prepared2.value.id.toString()),
            byEntity.keys,
            "value facts must be for proper entities"
        )

        val childFact =
            byEntity[prepared2.value.id.toString()]?.firstOrNull() ?: throw IllegalStateException("no fact for child value")
        val parentFact =
            byEntity[prepared1.value.id.toString()]?.firstOrNull() ?: throw IllegalStateException("no fact for child value")

        val childEvent = childFact.event
        val childPayload = childFact.payload
        assertEquals(OBJECT_PROPERTY_VALUE_CLASS, childEvent.entityClass, "class must be correct")
        assertEquals(EventType.CREATE, childEvent.type, "event type must be correct")

        assertEquals(setOf("typeTag", "strValue"), childPayload.data.keys, "data keys must be correct")
        assertEquals(emptySet(), childPayload.removedLinks.keys, "there must be no removed links")
        assertEquals(
            setOf("objectProperty", "parentValue"),
            childPayload.addedLinks.keys,
            "added links keys must be correct"
        )

        assertEquals(ScalarTypeTag.STRING.name, childPayload.data["typeTag"], "type tag must be correct")
        assertEquals(value2, childPayload.data["strValue"], "value must be correct")

        val propertyLinks = childPayload.addedLinks["objectProperty"] ?: fail("unexpected absence of property links")
        assertEquals(1, propertyLinks.size, "only 1 property must be here")
        assertEquals(prepared2.propertyId, propertyLinks.first().toString(), "property id must be correct")

        val parentLinks = childPayload.addedLinks["parentValue"] ?: fail("unexpected absence of parent value links")
        assertEquals(1, parentLinks.size, "only 1 parent value link must be here")
        assertEquals(prepared1.value.id, parentLinks.first(), "parent id must be correct")

        val parentEvent = parentFact.event
        val parentPayload = parentFact.payload

        assertEquals(OBJECT_PROPERTY_VALUE_CLASS, parentEvent.entityClass, "class must be correct")
        assertEquals(EventType.UPDATE, parentEvent.type, "event type must be correct")

        assertEquals(emptySet(), parentPayload.data.keys, "data keys must be correct")
        assertEquals(emptySet(), parentPayload.removedLinks.keys, "there must be no removed links")
        assertEquals(setOf("children"), parentPayload.addedLinks.keys, "added links keys must be correct")

        checkPropertyFacts(prepared2)
    }

    @Test
    fun createValueSubjectHistoryTest() {
        val testName = "createValueSubjectHistoryTest"
        val linkValue = ObjectValueData.Link(LinkValueData.Subject(subject.id))

        val prepared = prepareValue(testName, linkValue)
        val valueFacts = prepared.valueFacts

        assertEquals(1, valueFacts.size, "exactly one object property event must appear")
        val valueEvent = valueFacts.first().event
        val valuePayload = valueFacts.first().payload
        assertEquals(OBJECT_PROPERTY_VALUE_CLASS, valueEvent.entityClass, "class must be correct")
        assertEquals(EventType.CREATE, valueEvent.type, "event type must be correct")

        assertEquals(setOf("typeTag"), valuePayload.data.keys, "data keys must be correct")
        assertEquals(emptySet(), valuePayload.removedLinks.keys, "there must be no removed links")
        assertEquals(
            setOf("objectProperty", "refValueSubject"),
            valuePayload.addedLinks.keys,
            "added links keys must be correct"
        )

        assertEquals(ScalarTypeTag.SUBJECT.name, valuePayload.data["typeTag"], "type tag must be correct")

        val subjectLinks = valuePayload.addedLinks["refValueSubject"] ?: fail("unexpected absence of subject links")
        assertEquals(1, subjectLinks.size, "only 1 subject must be here")
        assertEquals(subject.id, subjectLinks.first().toString(), "value must be correct")

        val propertyLinks = valuePayload.addedLinks["objectProperty"] ?: fail("unexpected absence of property links")
        assertEquals(1, propertyLinks.size, "only 1 property must be here")
        assertEquals(prepared.propertyId, propertyLinks.first().toString(), "property id must be correct")

        checkPropertyFacts(prepared)
    }

    @Test
    fun createValueObjectHistoryTest() {
        val testName = "createValueObjectHistoryTest"
        val objectId = objectService.create(
            ObjectCreateRequest(
                name = "another_obj",
                description = null,
                subjectId = subject.id,
                subjectVersion = null
            ), "admin"
        )
        val linkValue = ObjectValueData.Link(LinkValueData.Object(objectId))

        val prepared = prepareValue(testName, linkValue)
        val valueFacts = prepared.valueFacts

        assertEquals(1, valueFacts.size, "exactly one object property event must appear")
        val valueEvent = valueFacts.first().event
        val valuePayload = valueFacts.first().payload
        assertEquals(OBJECT_PROPERTY_VALUE_CLASS, valueEvent.entityClass, "class must be correct")
        assertEquals(EventType.CREATE, valueEvent.type, "event type must be correct")

        assertEquals(setOf("typeTag"), valuePayload.data.keys, "data keys must be correct")
        assertEquals(emptySet(), valuePayload.removedLinks.keys, "there must be no removed links")
        assertEquals(
            setOf("objectProperty", "refValueObject"),
            valuePayload.addedLinks.keys,
            "added links keys must be correct"
        )

        assertEquals(ScalarTypeTag.OBJECT.name, valuePayload.data["typeTag"], "type tag must be correct")

        val objectLinks = valuePayload.addedLinks["refValueObject"] ?: fail("unexpected absence of object links")
        assertEquals(1, objectLinks.size, "only 1 object must be here")
        assertEquals(objectId, objectLinks.first().toString(), "value must be correct")

        val propertyLinks = valuePayload.addedLinks["objectProperty"] ?: fail("unexpected absence of property links")
        assertEquals(1, propertyLinks.size, "only 1 property must be here")
        assertEquals(prepared.propertyId, propertyLinks.first().toString(), "property id must be correct")

        checkPropertyFacts(prepared)
    }

    @Test
    fun createValueRefBookHistoryTest() {
        val testName = "createValueObjectHistoryTest"

        val refBook = refBookService.createReferenceBook("rb_$testName", aspect.id, "admin")
        val rbiId = refBookService.addReferenceBookItem(
            refBook.id,
            ReferenceBookItem(
                id = "",
                value = "rbi_$testName",
                description = null,
                children = emptyList(),
                deleted = false,
                version = 0
            ), "admin"
        )

        val linkValue = ObjectValueData.Link(LinkValueData.DomainElement(rbiId))

        val prepared = prepareValue(testName, linkValue)
        val valueFacts = prepared.valueFacts

        assertEquals(1, valueFacts.size, "exactly one object property event must appear")
        val valueEvent = valueFacts.first().event
        val valuePayload = valueFacts.first().payload
        assertEquals(OBJECT_PROPERTY_VALUE_CLASS, valueEvent.entityClass, "class must be correct")
        assertEquals(EventType.CREATE, valueEvent.type, "event type must be correct")

        assertEquals(setOf("typeTag"), valuePayload.data.keys, "data keys must be correct")
        assertEquals(emptySet(), valuePayload.removedLinks.keys, "there must be no removed links")
        assertEquals(
            setOf("objectProperty", "refValueDomainElement"),
            valuePayload.addedLinks.keys,
            "added links keys must be correct"
        )

        assertEquals(ScalarTypeTag.DOMAIN_ELEMENT.name, valuePayload.data["typeTag"], "type tag must be correct")

        val domainElementLinks =
            valuePayload.addedLinks["refValueDomainElement"] ?: fail("unexpected absence of domain element links")
        assertEquals(1, domainElementLinks.size, "only 1 domain element must be here")
        assertEquals(rbiId, domainElementLinks.first().toString(), "value must be correct")

        val propertyLinks = valuePayload.addedLinks["objectProperty"] ?: fail("unexpected absence of property links")
        assertEquals(1, propertyLinks.size, "only 1 property must be here")
        assertEquals(prepared.propertyId, propertyLinks.first().toString(), "property id must be correct")

        checkPropertyFacts(prepared)
    }

    @Test
    fun createValueWithAspectPropertyHistoryTest() {
        val testName = "createValueWithAspectPropertyHistoryTest"
        val value = "hello"

        val prepared = prepareValue(
            testName = testName,
            value = ObjectValueData.StringValue(value),
            aspectPropertyId = complexAspect.properties[0].id
        )
        val valueFacts = prepared.valueFacts

        assertEquals(1, valueFacts.size, "exactly one object property event must appear")
        val valueEvent = valueFacts.first().event
        val valuePayload = valueFacts.first().payload
        assertEquals(OBJECT_PROPERTY_VALUE_CLASS, valueEvent.entityClass, "class must be correct")
        assertEquals(EventType.CREATE, valueEvent.type, "event type must be correct")

        assertEquals(setOf("typeTag", "strValue"), valuePayload.data.keys, "data keys must be correct")
        assertEquals(emptySet(), valuePayload.removedLinks.keys, "there must be no removed links")
        assertEquals(
            setOf("objectProperty", "aspectProperty"),
            valuePayload.addedLinks.keys,
            "added links keys must be correct"
        )

        assertEquals(ScalarTypeTag.STRING.name, valuePayload.data["typeTag"], "type tag must be correct")
        assertEquals(value, valuePayload.data["strValue"], "value must be correct")

        val propertyLinks = valuePayload.addedLinks["objectProperty"] ?: fail("unexpected absence of property links")
        assertEquals(1, propertyLinks.size, "only 1 property must be here")
        assertEquals(prepared.propertyId, propertyLinks.first().toString(), "property id must be correct")

        val aspectPropertyLinks =
            valuePayload.addedLinks["aspectProperty"] ?: fail("unexpected absence of aspect property links")
        assertEquals(1, aspectPropertyLinks.size, "only 1 aspect property must be here")
        assertEquals(
            complexAspect.properties[0].id,
            aspectPropertyLinks.first().toString(),
            "aspect property id must be correct"
        )

        checkPropertyFacts(prepared)
    }

    @Test
    fun createValueWithMeasureHistoryTest() {
        val testName = "createValueWithMeasureHistoryTest"
        val value = "hello"
        val measure = measureService.findMeasure(VoltAmpere.name)

        val prepared = prepareValue(
            testName = testName, value = ObjectValueData.StringValue(value),
            aspectPropertyId = complexAspect.properties[0].id, measureId = measure?.id
        )
        val valueFacts = prepared.valueFacts

        assertEquals(1, valueFacts.size, "exactly one object property event must appear")
        val valueEvent = valueFacts.first().event
        val valuePayload = valueFacts.first().payload
        assertEquals(OBJECT_PROPERTY_VALUE_CLASS, valueEvent.entityClass, "class must be correct")
        assertEquals(EventType.CREATE, valueEvent.type, "event type must be correct")

        assertEquals(setOf("typeTag", "strValue"), valuePayload.data.keys, "data keys must be correct")
        assertEquals(emptySet(), valuePayload.removedLinks.keys, "there must be no removed links")
        assertEquals(
            setOf("objectProperty", "aspectProperty", "measure"),
            valuePayload.addedLinks.keys,
            "added links keys must be correct"
        )

        assertEquals(ScalarTypeTag.STRING.name, valuePayload.data["typeTag"], "type tag must be correct")
        assertEquals(value, valuePayload.data["strValue"], "value must be correct")

        val propertyLinks = valuePayload.addedLinks["objectProperty"] ?: fail("unexpected absence of property links")
        assertEquals(1, propertyLinks.size, "only 1 property must be here")
        assertEquals(prepared.propertyId, propertyLinks.first().toString(), "property id must be correct")

        val aspectPropertyLinks =
            valuePayload.addedLinks["aspectProperty"] ?: fail("unexpected absence of aspect property links")
        assertEquals(1, aspectPropertyLinks.size, "only 1 aspect property must be here")
        assertEquals(
            complexAspect.properties[0].id,
            aspectPropertyLinks.first().toString(),
            "aspect property id must be correct"
        )

        val measureLinks = valuePayload.addedLinks["measure"] ?: fail("unexpected absence of measure links")
        assertEquals(1, measureLinks.size, "only 1 measure must be here")
        assertEquals(measure?.id, measureLinks.first().toString(), "measure id must be correct")

        checkPropertyFacts(prepared)
    }

    private fun eventsByClass(events: Set<HistoryFact>, entityClass: String) =
      events.filter { it.event.entityClass == entityClass }

    private fun objectEvents(events: Set<HistoryFact>) = eventsByClass(events, OBJECT_CLASS)

    private fun subjectEvents(events: Set<HistoryFact>) = eventsByClass(events, SUBJECT_CLASS)

    private fun propertyEvents(events: Set<HistoryFact>) = eventsByClass(events, OBJECT_PROPERTY_CLASS)

    private fun valueEvents(events: Set<HistoryFact>) = eventsByClass(events, OBJECT_PROPERTY_VALUE_CLASS)

    private fun createObject(name: String): String {
        val request = ObjectCreateRequest(name, "object descr", subject.id, subject.version)
        return objectService.create(request, "user")
    }
}
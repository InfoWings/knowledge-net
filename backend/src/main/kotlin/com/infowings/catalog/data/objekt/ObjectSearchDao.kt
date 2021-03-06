package com.infowings.catalog.data.objekt

import com.infowings.catalog.storage.*
import com.orientechnologies.orient.core.id.ORID
import com.orientechnologies.orient.core.sql.executor.OResult
import notDeletedSql


class ObjectSearchDao(private val db: OrientDatabase) {

    @Suppress("UNUSED_PARAMETER")
    private fun acceptAll(result: OResult) = true

    private fun filter(subjectsGuids: List<String>, excludeFromSubjectFilter: List<String>): (OResult) -> Boolean =
        { result -> result.getProperty<String>("subjectGuid") in subjectsGuids || result.getProperty<String>(ATTR_GUID) in excludeFromSubjectFilter }

    fun searchObjectsTruncated(pattern: String, subjectsGuids: List<String>?, excludeFromSubjectFilter: List<String>): List<ObjectTruncated> {
        // bug in Orient Db prevent using graph traversing and lucene query in one WHERE clause so filtering applied to result instead of querying required guids
        val filter: (OResult) -> Boolean =
            if (subjectsGuids.isNullOrEmpty())
                ::acceptAll
            else
                filter(subjectsGuids, excludeFromSubjectFilter)

        return transaction(db) {
            val searchResult = searchInNameAndDescription(pattern, filter) +
                    searchInStringValues(pattern, filter) +
                    searchInReferenceBook(pattern, filter)
            return@transaction searchResult.distinctBy { it.id }
        }
    }

    private val allObjectsTruncated = """SELECT @rid, $ATTR_NAME, $ATTR_DESC, $ATTR_GUID, $ATTR_LAST_UPDATE, """ +
            """FIRST(OUT("$OBJECT_SUBJECT_EDGE")).name as subjectName, """ +
            """IN("$OBJECT_OBJECT_PROPERTY_EDGE").size() as objectPropertiesCount """ +
            """FROM $OBJECT_CLASS WHERE (deleted is NULL or deleted = false ) """

    fun getAllObjectsFilteredBySubjectTruncated(subjectsGuids: List<String>?, excludeFromSubjectFilter: List<String>): List<ObjectTruncated> =
        transaction(db) {
            var query = allObjectsTruncated

            if (!subjectsGuids.isNullOrEmpty())
                query += """ AND FIRST(OUT("$OBJECT_SUBJECT_EDGE")).guid in :guids """
            if (excludeFromSubjectFilter.isNotEmpty())
                query += """ OR guid in :excludeGuids """

            return@transaction db.query(query, mapOf("guids" to subjectsGuids, "excludeGuids" to excludeFromSubjectFilter)) { it.toObjectsTruncated() }
        }


    private fun searchInNameAndDescription(pattern: String, filter: (OResult) -> Boolean): List<ObjectTruncated> {
        val query = baseSelectForTruncatedObjects +
                "WHERE ${anyOfCond(listOf(searchLucene(OBJECT_CLASS, ATTR_NAME, "lq"), searchLucene(OBJECT_CLASS, ATTR_DESC, "lq")))}\n" +
                " and $notDeletedSql \n"
        return db.query(query, mapOf("lq" to luceneQuery(textOrAllWildcard(pattern)))) { it.filter(filter).toObjectsTruncated() }
    }

    private fun searchInStringValues(pattern: String, filter: (OResult) -> Boolean): List<ObjectTruncated> {
        val queryByStr = baseSelectForTruncatedObjects +
                "      LET \$t = (SELECT OUT(\"${OrientEdge.OBJECT_PROPERTY_OF_OBJECT_VALUE.extName}\") AS prop, \n" +
                "                        prop.OUT(\"${OrientEdge.OBJECT_OF_OBJECT_PROPERTY.extName}\").name AS obj_name, \n" +
                "                        out(\"${OrientEdge.OBJECT_PROPERTY_OF_OBJECT_VALUE.extName}\") \n" +
                "                           .out(\"${OrientEdge.OBJECT_OF_OBJECT_PROPERTY.extName}\").@rid AS obj_id,\n" +
                "                         out(\"${OrientEdge.OBJECT_PROPERTY_OF_OBJECT_VALUE.extName}\").deleted AS prop_deleted,\n" +
                "                    $STR_TYPE_PROPERTY, $TYPE_TAG_PROPERTY, deleted  FROM ${OrientClass.OBJECT_VALUE.extName}\n" +
                "          WHERE $TYPE_TAG_PROPERTY = ${ScalarTypeTag.STRING.code}\n" +
                "                 AND ${searchLucene(OrientClass.OBJECT_VALUE.extName, STR_TYPE_PROPERTY, "lq")}\n" +
                "                 AND $notDeletedSql AND (prop_deleted is null OR prop_deleted = false)\n" +
                "          UNWIND obj_name, obj_id, prop_deleted) WHERE @rid IN \$t.obj_id  " +
                " and $notDeletedSql "
        return db.query(queryByStr, mapOf("lq" to luceneQuery(textOrAllWildcard(pattern)))) { it.filter(filter).toObjectsTruncated() }
    }

    private fun searchInReferenceBook(pattern: String, filter: (OResult) -> Boolean): List<ObjectTruncated> {
        val queryByRefBook = baseSelectForTruncatedObjects +
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
                " and $notDeletedSql "
        return db.query(queryByRefBook, mapOf("lq" to luceneQuery(textOrAllWildcard(pattern)))) { it.filter(filter).toObjectsTruncated() }
    }


    private fun Sequence<OResult>.toObjectsTruncated() = map {
        val id: ORID = it.getProperty("@rid")
        ObjectTruncated(
            id,
            it.getProperty("name"),
            it.getProperty("guid"),
            it.getProperty("description"),
            it.getProperty("subjectName") ?: "UNKNOWN",
            it.getProperty("objectPropertiesCount"),
            lastUpdated = it.getProperty(ATTR_LAST_UPDATE)
        )
    }.toList()

}

private fun luceneIdx(classType: String, attr: String) = "\"$classType.lucene.$attr\""
private fun searchLucene(classType: String, attr: String, patternBinding: String) =
    "( SEARCH_INDEX(${luceneIdx(classType, attr)}, :$patternBinding) = true)"

private fun luceneQuery(text: String) = "($text~) ($text*) (*$text*)"
private fun anyOfCond(conditions: List<String>) = conditions.joinToString(" or ", "(", ")")
private fun textOrAllWildcard(text: String?): String = if (text.isNullOrBlank()) "*" else text.trim()

private const val baseSelectForTruncatedObjects =
    "SELECT @rid, $ATTR_NAME, $ATTR_DESC, $ATTR_GUID, $ATTR_LAST_UPDATE, \n" +
            "FIRST(OUT(\"$OBJECT_SUBJECT_EDGE\")).name as subjectName, \n" +
            "FIRST(OUT(\"$OBJECT_SUBJECT_EDGE\")).guid as subjectGuid, \n" +
            "IN(\"$OBJECT_OBJECT_PROPERTY_EDGE\").size() as objectPropertiesCount \n" +
            "FROM $OBJECT_CLASS \n"

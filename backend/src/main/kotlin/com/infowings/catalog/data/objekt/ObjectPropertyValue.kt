package com.infowings.catalog.data.objekt

import com.infowings.catalog.common.*
import com.infowings.catalog.data.aspect.AspectPropertyVertex
import com.infowings.catalog.data.aspect.AspectVertex
import com.infowings.catalog.storage.id
import com.orientechnologies.orient.core.id.ORID
import com.orientechnologies.orient.core.record.OVertex

/**
 * Object property value data representation for use in backend context.
 * It can use Orient data structures but it is detached from database - updated to it does not lead to
 * updates in database
 */
data class ObjectPropertyValue(
    val id: ORID?,
    val value: ScalarValue?,
    val range: Range?,
    val precision: Int?,
    val objectProperty: ObjectPropertyVertex,
    val characteristics: List<CharacteristicVertex>
) {
    fun toObjectPropertyValueData(): ObjectPropertyValueData {
        return ObjectPropertyValueData(id?.toString(), value, range, precision, objectProperty.id,
            characteristics.map {CharacteristicData(it.id, it.type())})
    }
}


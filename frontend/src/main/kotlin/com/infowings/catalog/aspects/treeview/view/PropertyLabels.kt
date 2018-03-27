package com.infowings.catalog.aspects.treeview.view

import kotlinx.html.js.onClickFunction
import react.RBuilder
import react.dom.div
import react.dom.span

fun RBuilder.propertyLabel(
        className: String?,
        aspectPropertyName: String,
        aspectPropertyCardinality: String,
        aspectName: String,
        aspectMeasure: String,
        aspectDomain: String,
        aspectBaseType: String,
        onClick: () -> Unit
) = div(classes = "aspect-tree-view--label${className?.let { " $it" } ?: ""}") {
    attrs {
        onClickFunction = {
            it.stopPropagation()
            it.preventDefault()
            onClick()
        }
    }
    span(classes = "aspect-tree-view--label-property-name") {
        +aspectPropertyName
    }
    span(classes = "aspect-tree-view--label-name") {
        +(aspectName)
    }
    +":"
    span(classes = "aspect-tree-view--label-property") {
        +"["
        span(classes = "aspect-tree-view--label-property-cardinality") {
            +aspectPropertyCardinality
        }
        +"]"
    }
    +":"
    span(classes = "aspect-tree-view--label-measure") {
        +aspectMeasure
    }
    +":"
    span(classes = "aspect-tree-view--label-domain") {
        +aspectDomain
    }
    +":"
    span(classes = "aspect-tree-view--label-base-type") {
        +aspectBaseType
    }
}

fun RBuilder.placeholderPropertyLabel(className: String?) =
        div(classes = "aspect-tree-view--label${className?.let { " $it" } ?: ""}") {
            +"(Enter new Aspect Property)"
        }

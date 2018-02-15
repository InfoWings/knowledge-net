package catalog.aspects

import catalog.layout.Header
import react.RBuilder
import react.RComponent
import react.RState
import react.dom.h1
import wrappers.RouteSuppliedProps


class AspectsPage : RComponent<RouteSuppliedProps, RState>() {

    override fun RBuilder.render() {
        child(Header::class) {
            attrs { location = props.location.pathname }
        }
        h1 { +"Aspects Page" }
        child(AspectsTable::class) {}
    }
}

package com.infowings.catalog.aspects.filter

import com.infowings.catalog.common.SubjectData
import com.infowings.catalog.subjects.getSuggestedSubject
import com.infowings.catalog.utils.JobCoroutineScope
import com.infowings.catalog.utils.JobSimpleCoroutineScope
import com.infowings.catalog.wrappers.select.SelectOption
import com.infowings.catalog.wrappers.select.asyncSelect
import kotlinext.js.jsObject
import kotlinext.js.require
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import react.*

private interface SubjectOption : SelectOption {
    var subjectName: String
    var subjectData: SubjectData?
}

private fun subjectOption(subjectData: SubjectData?) = jsObject<SubjectOption> {
    this.subjectName = subjectData?.name ?: "Global"
    this.subjectData = subjectData
}

class AspectSubjectFilterComponent : RComponent<AspectSubjectFilterComponent.Props, RState>(), JobCoroutineScope by JobSimpleCoroutineScope() {
    override fun componentWillMount() {
        job = Job()
    }

    override fun componentWillUnmount() {
        job.cancel()
    }

    companion object {
        init {
            require("styles/aspect-filter.scss")
        }
    }

    override fun RBuilder.render() {
        asyncSelect<SubjectOption> {
            attrs {
                className = "aspect-subject-filter"
                multi = true
                placeholder = "Filter by subject..."
                value = props.subjectsFilter.map { subjectOption(it) }.toTypedArray()
                labelKey = "subjectName"
                valueKey = "subjectName"
                cache = false
                onChange = {
                    props.onChange(it.unsafeCast<Array<SubjectOption>>().map { it.subjectData }) // TODO: KS-143
                }
                filterOptions = { options, _, _ -> options }
                loadOptions = { input, callback ->
                    if (input.isNotEmpty()) {
                        launch {
                            val filteredSubjects = getSuggestedSubject(input, "").subject
                            val filteredSubjectsPlusNull = if ("Global".contains(input, ignoreCase = true))
                                filteredSubjects.plus<SubjectData?>(null) else filteredSubjects
                            callback(null, jsObject {
                                options = filteredSubjectsPlusNull.map { subjectOption(it) }.toTypedArray()
                            })
                        }
                    } else {
                        callback(null, jsObject {
                            options = arrayOf(subjectOption(null))
                        })
                    }
                    false
                }
                clearable = false
            }
        }
    }

    interface Props : RProps {
        var subjectsFilter: Collection<SubjectData?>
        var onChange: (List<SubjectData?>) -> Unit
    }
}

fun RBuilder.aspectSubjectFilterComponent(block: RHandler<AspectSubjectFilterComponent.Props>) =
    child(AspectSubjectFilterComponent::class, block)
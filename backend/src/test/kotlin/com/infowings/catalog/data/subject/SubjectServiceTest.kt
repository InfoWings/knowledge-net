package com.infowings.catalog.data.subject

import com.infowings.catalog.MasterCatalog
import com.infowings.catalog.common.*
import com.infowings.catalog.createTestAspect
import com.infowings.catalog.data.Subject
import com.infowings.catalog.data.SubjectService
import com.infowings.catalog.data.aspect.AspectAlreadyExist
import com.infowings.catalog.data.aspect.AspectService
import com.infowings.catalog.data.toSubjectData
import org.junit.Test
import org.junit.runner.RunWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner

@RunWith(SpringJUnit4ClassRunner::class)
@SpringBootTest(classes = [MasterCatalog::class])
class SubjectServiceTest {

    @Autowired
    lateinit var subjectService: SubjectService

    @Autowired
    private lateinit var aspectService: AspectService


    @Test(expected = AspectAlreadyExist::class)
    fun testAddAspectsSameNameSameSubject() {
        val subject = createTestSubject("TestSubjectUpdate")
        val ad1 = AspectData(
            "",
            "aspect",
            Kilometre.name,
            null,
            BaseType.Decimal.name,
            emptyList(),
            subject = subject.toSubjectData()
        )
        aspectService.save(ad1)

        val ad2 = AspectData(
            "",
            "aspect",
            Metre.name,
            null,
            BaseType.Decimal.name,
            emptyList(),
            subject = subject.toSubjectData()
        )
        aspectService.save(ad2)
    }

    private fun createTestSubject(name: String, aspectNames: List<String> = listOf("TestSubjectAspect")): Subject =
        createTestSubject(name, aspectNames, aspectService, subjectService)
}

fun createTestSubject(
    name: String,
    aspectNames: List<String> = listOf("TestSubjectAspect"),
    aspectService: AspectService,
    subjectService: SubjectService
): Subject {
    val aspects = aspectNames.map { createTestAspect(it, aspectService) }
    val sd = SubjectData(name = name, aspects = aspects.map { AspectData(it.id) })
    return subjectService.findByName(name) ?: subjectService.createSubject(sd)
}
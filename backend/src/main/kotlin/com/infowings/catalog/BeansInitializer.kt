package com.infowings.catalog

import com.infowings.catalog.auth.UserAcceptService
import com.infowings.catalog.data.MeasureService
import com.infowings.catalog.data.ReferenceBookDaoService
import com.infowings.catalog.data.ReferenceBookService
import com.infowings.catalog.data.SubjectService
import com.infowings.catalog.data.aspect.AspectDaoService
import com.infowings.catalog.data.aspect.AspectService
import com.infowings.catalog.data.history.HistoryDaoService
import com.infowings.catalog.data.history.HistoryService
import com.infowings.catalog.search.SuggestionService
import com.infowings.catalog.storage.DEFAULT_HEART_BEAT__TIMEOUT
import com.infowings.catalog.storage.OrientDatabase
import com.infowings.catalog.storage.OrientHeartBeat
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.support.GenericApplicationContext
import org.springframework.context.support.beans
import org.springframework.core.env.get

/**
 * Выносим в отдельный файл и прописываем в application.properties
 * для того, чтобы beans были видны и в контексе приложения, и в контексте
 * тестов
 *
 * Цитата:
 * ----------------------------------------
 * The ApplicationContextInitializer approach seems to work for this basic use case,
 * but unlike previous example have chosen to use an context.initializer.classes entry
 * in the application.properties file, the big advantage compared to SpringApplication.addInitializers()
 * being that the beans are taken in account also for tests.
 * I have updated the related StackOverflow answer accordingly.
 * ------------------------------------------
 *
 * https://github.com/spring-projects/spring-boot/issues/8115#issuecomment-327829617
 * https://stackoverflow.com/questions/45935931/how-to-use-functional-bean-definition-kotlin-dsl-with-spring-boot-and-spring-w/46033685
 */
class BeansInitializer : ApplicationContextInitializer<GenericApplicationContext> {
    override fun initialize(ctx: GenericApplicationContext) = beans {
        bean { UserAcceptService(database = ref()) }
        bean { MeasureService(database = ref()) }
        bean {ReferenceBookDaoService(db = ref())}
        bean { ReferenceBookService(database = ref(), daoService = ref(), historyService = ref()) }
        bean { AspectDaoService(db = ref(), measureService = ref()) }
        bean { AspectService(db = ref(), aspectDaoService = ref(), historyService = ref()) }
        bean { SubjectService(db = ref(), aspectService = ref()) }
        bean { SuggestionService(database = ref()) }
        bean { HistoryDaoService(db = ref()) }
        bean { HistoryService(db = ref(), historyDaoService = ref(), userAcceptService = ref()) }

        bean {
            OrientDatabase(
                url = env["orient.url"],
                database = env["orient.database"],
                user = env["orient.user"],
                password = env["orient.password"]
            )
        }

        bean {
            OrientHeartBeat(
                database = ref(),
                seconds = env.getProperty("orient.heartbeat.timeout")?.toInt() ?: DEFAULT_HEART_BEAT__TIMEOUT
            )
        }
    }.initialize(ctx)
}
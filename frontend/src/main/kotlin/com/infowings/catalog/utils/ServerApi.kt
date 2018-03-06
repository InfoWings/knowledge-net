package com.infowings.catalog.utils


import com.infowings.catalog.common.JwtToken
import com.infowings.catalog.common.UserDto
import kotlinx.coroutines.experimental.await
import org.w3c.fetch.RequestCredentials
import org.w3c.fetch.RequestInit
import org.w3c.fetch.Response
import kotlin.browser.document
import kotlin.browser.window
import kotlin.js.*
import kotlin.js.JSON
import kotlinx.serialization.json.JSON as KJSON

private const val POST = "POST"
private const val GET = "GET"

private const val AUTH_ROLE = "auth-role"

private external fun encodeURIComponent(component: String): String = definedExternally

/**
 * Http POST request to server.
 * Return object of type T which is obtained by parsing response text.
 */
suspend fun post(url: String, body: dynamic): String {
    return authorizedRequest(POST, url, body).text().await()
}

/**
 * Http GET request to server.
 * Return object of type T which is obtained by parsing response text.
 */
suspend fun get(url: String, body: dynamic = null): String {
    return authorizedRequest(GET, url, body).text().await()
}

/**
 * Http request to server after authorization.
 * If response status 200(OK) then return response
 * if response status 401(unauthorized) then remove role cookie and redirect to login page
 * if response status 403(forbidden) then refresh token and repeat request
 * else throw ServerException
 */
private suspend fun authorizedRequest(method: String, url: String, body: dynamic, repeat: Boolean = false): Response {
    val response = request(method, url, body)
    val statusCode = response.status.toInt()

    return when (statusCode) {
    // ok
        200 -> response

    // unauthorized
        401 -> {
            redirectToLoginPage()
            response
        }

    // forbidden
        403 -> {
            if (repeat) {
                redirectToLoginPage()
                response
            } else {
                refreshTokenAndRepeatRequest(method, url, body)
            }
        }

        else -> throw ServerException(response.text().await(), statusCode)
    }
}

class ServerException(message: String, val httpStatusCode: Int) : RuntimeException(message)

private fun redirectToLoginPage() {
    logout()
    window.location.replace("/")
}

/**
 * Generic request to server with default headers.
 */
private suspend fun request(method: String, url: String, body: dynamic, headers: dynamic = defaultHeaders): Response =
    window.fetch(url, object : RequestInit {
        override var method: String? = method
        override var body: dynamic = body
        override var credentials: RequestCredentials? = "same-origin".asDynamic()
        override var headers: dynamic = headers
    }).await()

private val defaultHeaders = json(
    "Accept" to "application/json",
    "Content-Type" to "application/json;charset=UTF-8"
)

/**
 * Method that try to refresh token and repeat request.
 * If refreshing was successful then return response to repeat request else return response to refreshing request
 */
private suspend fun refreshTokenAndRepeatRequest(method: String, url: String, body: dynamic): Response {
    val responseToRefresh = request(GET, "/api/access/refresh", null)
    val refreshStatus = responseToRefresh.status.toInt()
    return when (refreshStatus) {
        200 -> {
            parseToken(responseToRefresh)
            authorizedRequest(method, url, body, repeat = true)
        }
        401 -> {
            redirectToLoginPage()
            responseToRefresh
        }
        else -> throw ServerException(responseToRefresh.text().await(), refreshStatus)
    }
}

/**
 * Method for login to server.
 * After success login authorization token saved in local storage
 */
suspend fun login(body: UserDto): Boolean {
    val response = request(POST, "/api/access/signIn", JSON.stringify(body))
    return if (response.ok) {
        try {
            parseToken(response)
            true
        } catch (e: TokenParsingException) {
            console.log(e.message)
            false
        }
    } else {
        false
    }
}

/**
 * Parse token save in cookies
 */
private suspend fun parseToken(response: Response) {
    try {
        var ms: dynamic
        val jwtToken = JSON.parse<JwtToken>(response.text().await())
        val nowInMs = Date.now()
        ms = jwtToken.accessTokenExpirationTimeInMs.asDynamic() + nowInMs
        val accessExpireDate = Date(ms as Number).toUTCString()
        ms = jwtToken.refreshTokenExpirationTimeInMs.asDynamic() + nowInMs
        val refreshExpireDate = Date(ms as Number).toUTCString()
        document.cookie =
                "x-access-authorization=${encodeURIComponent("Bearer ${jwtToken.accessToken}")};expires=$accessExpireDate;path=/"
        document.cookie =
                "x-refresh-authorization=${encodeURIComponent("Bearer ${jwtToken.refreshToken}")};expires=$refreshExpireDate;path=/"
        document.cookie = "$AUTH_ROLE=${jwtToken.role}"
    } catch (e: Exception) {
        throw TokenParsingException(e)
    }
}

class TokenParsingException(e: Exception) : RuntimeException(e) {
    override val message: String?
        get() = "TokenParsingException caused by:\n${super.message}"
}

/**
 * Return authorization role name that saved in cookies.
 */
fun getAuthorizationRole(): String? {
    return document.cookie.split("; ")
        .filter { it.startsWith(AUTH_ROLE) }
        .map { it.split("=")[1] }
        .filter { it.isNotEmpty() }
        .getOrNull(0)
}

fun logout() {
    document.cookie = "$AUTH_ROLE="
}
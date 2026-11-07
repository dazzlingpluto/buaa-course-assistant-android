package org.buaacourse.assistant

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class AcademicAccessProtocolTest {
    @Test fun portalResponseDeterminesRouteWithoutInspectingStudentNumber() {
        val byxt = "https://byxt.buaa.edu.cn/jwapp/sys/homeapp/api/home/currentUser.do".toHttpUrl()
        assertEquals(AcademicAccessProtocol.Portal.UNDERGRADUATE, AcademicAccessProtocol.portal(200, byxt, "{\"code\":0}"))
        assertEquals(AcademicAccessProtocol.Portal.GRADUATE, AcademicAccessProtocol.portal(200,
            "https://byxt.buaa.edu.cn/jwapp/sys/byrhmhsy/index.do".toHttpUrl(), "{}"))
        assertEquals(AcademicAccessProtocol.Portal.UNAVAILABLE, AcademicAccessProtocol.portal(200, byxt, "<html>portal</html>"))
        assertEquals(AcademicAccessProtocol.Portal.SIGN_IN, AcademicAccessProtocol.portal(401, byxt, "{}"))
    }
    @Test fun onlyDefaultPortCasHttpRedirectIsUpgradedAndVpnRemainsInsideGateway() {
        val from = "https://byxt.buaa.edu.cn/".toHttpUrl()
        val target = "http://sso.buaa.edu.cn/login?service=https%3A%2F%2Fbyxt.buaa.edu.cn%2Fcallback%3Fx%3D1"
        val direct = IclassProtocol.academicRedirect(from, target, NetworkMode.DIRECT)
        assertEquals("https", direct.scheme)
        assertEquals("https://byxt.buaa.edu.cn/callback?x=1", direct.queryParameter("service"))
        assertEquals("d.buaa.edu.cn", IclassProtocol.academicRedirect(from, target, NetworkMode.VPN).host)
        assertTrue(runCatching { IclassProtocol.academicRedirect(from, "http://byxt.buaa.edu.cn/", NetworkMode.DIRECT) }.isFailure)
        assertTrue(runCatching { IclassProtocol.academicRedirect(from, "http://sso.buaa.edu.cn:8080/", NetworkMode.DIRECT) }.isFailure)
    }
    @Test fun publicOrInvalidUcResponseCannotBecomeAuthenticatedSession() {
        for (body in listOf("{}", "{\"code\":0,\"data\":{}}", "{\"code\":1,\"data\":{\"schoolid\":\"123\"}}"))
            assertTrue(runCatching { AcademicAccessProtocol.authenticatedUser(body) }.isFailure)
        assertEquals("123", AcademicAccessProtocol.authenticatedUser("{\"code\":0,\"data\":{\"schoolid\":\"123\",\"name\":\"演示用户\"}}").userId)
    }
}

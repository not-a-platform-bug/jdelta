package jdelta.cli

import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.isRegularFile

/** CLI 배포본(`<home>/lib/jdelta-cli.jar` 기준)의 부속 파일. test에서는 system property로 바꾼다. */
internal object Distribution {
    fun file(property: String, relative: String): Path? {
        System.getProperty(property)?.let { return Path(it).takeIf { p -> p.isRegularFile() } }
        val location = Distribution::class.java.protectionDomain?.codeSource?.location ?: return null
        val home = Path.of(location.toURI()).parent?.parent ?: return null
        return home.resolve(relative).takeIf { it.isRegularFile() }
    }

    fun agentJar(): Path? = file("jdelta.agentJar", "agent/jdelta-agent.jar")

    fun junitJar(): Path? = file("jdelta.junitJar", "agent/jdelta-junit.jar")
}

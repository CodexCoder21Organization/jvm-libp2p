```groovy
import org.gradle.api.publish.maven.MavenPublication

gradle.projectsEvaluated {
    allprojects {
        version = "1.3.0-codexcoder21-snapshot-38"
        plugins.withId("maven-publish") {
            publishing.publications.withType(MavenPublication).configureEach {
                version = "1.3.0-codexcoder21-snapshot-38"
            }
        }
    }
    println "PUBLIBP2P release version: " + rootProject.version
}
```

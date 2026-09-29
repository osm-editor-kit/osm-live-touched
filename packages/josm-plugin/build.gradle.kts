plugins {
  java
  id("org.openstreetmap.josm") version "0.8.2"
}

repositories {
  mavenCentral()
}

dependencies {
  testImplementation(platform("org.junit:junit-bom:5.11.4"))
  testImplementation("org.junit.jupiter:junit-jupiter")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

josm {
  pluginName = "live-touched"
  // The JOSM version installed locally (Tobias: 19481); the plugin must run there.
  josmCompileVersion = "19481"
  manifest {
    description = "See live which OSM objects other mappers are editing near you, before they upload (opt-in)."
    mainClass = "org.openstreetmap.josm.plugins.livetouched.LiveTouchedPlugin"
    minJosmVersion = "19481"
    author = "Tobias Jordans"
    canLoadAtRuntime = true
    iconPath = "images/dialogs/live-touched.svg"
    website = uri("https://github.com/osm-editor-kit/osm-live-touched").toURL()
  }
}

// JOSM runs on Java 11+ (its jar is class version 55).
tasks.withType<JavaCompile>().configureEach {
  options.release.set(11)
  options.encoding = "UTF-8"
}

tasks.test {
  useJUnitPlatform()
}

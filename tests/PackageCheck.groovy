/**
 * packageManifest.json against the code, so an HPM install or update gets every
 * file at one version: each app and driver is listed once with the name and
 * namespace of its definition, its importUrl is its manifest location, and the
 * manifest version is the version stamped in every file (scripts/release.sh).
 */

import groovy.json.JsonSlurper
import static Check.check

File root = new File(getClass().protectionDomain.codeSource.location.path).parentFile.parentFile
Map m = new JsonSlurper().parse(new File(root, "packageManifest.json")) as Map
List<Map> entries = ((m.apps ?: []) + (m.drivers ?: [])) as List<Map>
List<File> files = (new File(root, "apps").listFiles() + new File(root, "drivers").listFiles())
    .findAll { it.name.endsWith(".groovy") }.sort { it.path }
String base = "https://raw.githubusercontent.com/digital-homesteaders/hubitat-hue/main/"
String rel(File f) { f.parentFile.name + "/" + f.name }

check("the manifest has a release version and one primary app") {
    assert m.packageName == "Hue Connect" && m.version ==~ /\d+\.\d+\.\d+/ && m.dateReleased ==~ /\d{4}-\d{2}-\d{2}/
    assert m.apps.size() == 1 && m.apps[0].primary == true && m.apps[0].name == "Hue Connect"
}

check("every file is stamped with the manifest version") {
    Map stamped = files.collectEntries { f ->
        def v = (f.text =~ /static final String (?:DRIVER_)?VERSION = "([^"]+)"/)
        [(f.name): v.find() ? v.group(1) : "none"]
    }
    assert stamped.values().every { it == m.version }, "run scripts/release.sh: ${stamped} vs ${m.version}"
}

check("every app and driver is in the manifest once, and nothing else is") {
    assert entries.collect { (it.location as String) - base }.sort() == files.collect { rel(it) }.sort()
    assert entries.every { (it.location as String).startsWith(base) }
}

check("names, namespaces and import URLs match each file's definition") {
    files.each { File f ->
        Map e = entries.find { (it.location as String).endsWith("/" + rel(f)) }
        String t = f.text
        String name = (t =~ /definition\(\s*name:\s*"([^"]+)"/).with { it.find() ? it.group(1) : null } ?:
            (t =~ /(?m)^\s*name:\s*"([^"]+)"/).with { it.find() ? it.group(1) : null }
        String ns = (t =~ /namespace:\s*"([^"]+)"/).with { it.find() ? it.group(1) : null }
        String imp = (t =~ /importUrl:\s*"([^"]*)"/).with { it.find() ? it.group(1) : null }
        assert e.name == name && e.namespace == ns, f.name
        assert imp == e.location, "${f.name}: importUrl ${imp} vs ${e.location}"
    }
}

check("only the app and the Hue Bridge are required; every other driver is an optional pick in HPM") {
    Map required = entries.collectEntries { [(it.name): it.required] }
    ["Hue Connect", "Hue Bridge"].each { assert required[it] == true, it }
    ["Hue Color Light", "Hue White Ambiance Light", "Hue White Light", "Hue Plug", "Hue Group", "Hue Scene",
     "Hue Button", "Hue Motion Sensor", "Hue Contact Sensor"].each { assert required[it] == false, it }
    assert required.findAll { it.value }.keySet() == ["Hue Connect", "Hue Bridge"] as Set, "a new driver must be listed above, as required or optional"
}

check("ids are unique UUIDs; the licence points at NOTICE, which credits Hue's docs and the projects learned from") {
    List ids = entries*.id
    assert ids.every { it ==~ /[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/ } && ids.unique(false).size() == ids.size()
    assert new File(root, "LICENSE").text.contains("https://github.com/digital-homesteaders/hubitat-hue/blob/main/NOTICE")
    String notice = new File(root, "NOTICE").text
    assert notice.contains("Signify") && notice.contains("aiohue") && notice.contains("CoCoHue")
}

Check.finish()

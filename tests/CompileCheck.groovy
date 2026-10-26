/**
 * Compile the app and every driver against the hub stand-ins and run their
 * top-level code, then check for things Hubitat's Groovy 2.4 sandbox refuses
 * or garbles.
 */

import static Check.check

File root = new File(getClass().protectionDomain.codeSource.location.path).parentFile.parentFile
Hub hub = new Hub(root)
List<File> files = (new File(root, "apps").listFiles() + new File(root, "drivers").listFiles())
    .findAll { it.name.endsWith(".groovy") }.sort { it.path }

files.each { File f ->
    check("compiles and loads: ${f.parentFile.name}/${f.name}") {
        HubitatScript s = hub.instance(f)
        assert s.callsNamed("definition"), "no definition(...)"
    }
}

check("the Hue Bridge driver is singleThreaded (stream, queue and timers never interleave)") {
    assert hub.instance(new File(root, "drivers/hue-bridge.groovy")).callsNamed("definition")[0][1].singleThreaded == true
}

List<String> dslWords = ["definition", "capability", "attribute", "command", "fingerprint", "input", "section", "page", "paragraph", "href", "label", "app"]
files.each { File f ->
    check("no method shadows a definition word: ${f.name}") {
        assert !hub.compile(f).declaredMethods*.name.unique().findAll { it in dslWords }
    }
}

files.each { File f ->
    check("no post-2.4 Groovy methods or sandbox-risky calls: ${f.name}") {
        List bad = ["takeRight(", "dropRight(", ".tap {", "=> ", "System.", ".clone()", "Thread.", "getClass()", "Arrays.",
                    "Class.forName", "@CompileStatic", "synchronized"].findAll { f.text.contains(it) }
        assert !bad
    }
}

files.each { File f ->
    check("ASCII only: ${f.name}") {
        List bad = f.readLines("UTF-8").withIndex().findAll { line, i -> line.any { (it as char) > 127 } }.collect { it[1] + 1 }
        assert !bad, "non-ASCII on lines ${bad}"
    }
}

files.each { File f ->
    check("no sandbox-blocked property names: ${f.name}") {
        def m = f.text =~ /(?m)^(?!\s*(\/\/|\*)).*?(\.(fields|properties|methods|declaredFields|declaredMethods|metaClass)\b|\[\s*"(fields|properties|methods|class|metaClass)"\s*\])/
        assert !m.find(), "found: ${m.group(0)?.trim()}"
    }
}

check("the app and every driver carry the same version") {
    Set versions = files.collect { f -> (f.text =~ /static final String (?:DRIVER_)?VERSION = "([^"]+)"/).with { it.find() ? it.group(1) : "none" } } as Set
    assert versions.size() == 1 && !("none" in versions), "versions: ${versions}"
}

check("every driver method the app calls exists and is public") {
    String app = new File(root, "apps/hue-connect.groovy").text
    Class bridge = hub.compile(new File(root, "drivers/hue-bridge.groovy"))
    ["enqueue", "initialize"].each { m -> assert bridge.methods.any { it.name == m }, "Hue Bridge lacks ${m}" }
    Class appClass = hub.compile(new File(root, "apps/hue-connect.groovy"))
    new File(root, "drivers").listFiles().findAll { it.name.endsWith(".groovy") }.each { File f ->
        (f.text =~ /parent\??\.(\w+)\s*\(/).collect { it[1] }.unique().each { String m ->
            assert appClass.methods.any { it.name == m && !java.lang.reflect.Modifier.isPrivate(it.modifiers) }, "${f.name} calls parent.${m}, which the app lacks"
        }
    }
}

check("the Hue docs never reach git") {
    // A checkout without git (a tarball) can't commit anything and a fresh clone (CI) has no docs,
    // so only a checkout with the docs (a directory or a symlink) needs git to ignore it
    Closure git = { List<String> args ->
        StringBuilder out = new StringBuilder(), err = new StringBuilder()
        Process p = (["git"] + args).execute(null, root)
        p.waitForProcessOutput(out, err)
        [code: p.exitValue(), out: out.toString().trim(), err: err.toString().trim()]
    }
    Map repo
    try { repo = git(["rev-parse", "--git-dir"]) } catch (IOException noGit) { return }
    if (repo.code != 0) {
        assert repo.err.contains("not a git repository"), "git rev-parse failed: ${repo.err}"
        return
    }
    Map tracked = git(["ls-files", "docs/hue-api"])
    assert tracked.code == 0, "git ls-files failed: ${tracked.err}"
    assert tracked.out == "", "Hue docs are tracked: ${tracked.out}"
    if (new File(root, "docs/hue-api").exists()) {
        Map ignored = git(["check-ignore", "-q", "docs/hue-api"])
        assert ignored.code == 0, "git does not ignore docs/hue-api: ${ignored.err ?: 'add docs/hue-api to .gitignore'}"
    }
    assert !new File(root, ".gitignore").text.contains("!docs/hue-api")
}

Check.finish()

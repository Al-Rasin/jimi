"""Run the saved-command subset on Android; all private data stays outside the repo."""
import argparse
import json
import os
from pathlib import Path
import shlex
import subprocess
import zipfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--device", required=True)
    parser.add_argument("--root", type=Path, default=Path("/private/tmp/jimi-stt"))
    parser.add_argument("--work", type=Path, default=Path("/private/tmp/jimi-stt2-phone"))
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[2]
    for path in (args.root, args.work):
        if path.resolve() == repo or repo in path.resolve().parents:
            parser.error("Private files must be outside the repository")
    work = args.work.resolve()
    work.mkdir(mode=0o700, parents=True, exist_ok=True)
    java_home = Path("/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home")
    sdk = Path.home() / "Library/Android/sdk"
    cache = Path.home() / ".gradle/caches/modules-2/files-2.1"

    def jar(group, artifact, version):
        return str(next((cache / group / artifact / version).glob("*/*.jar")))

    def run(command, **kwargs):
        return subprocess.run(command, check=True, text=True, stdout=subprocess.PIPE,
                              stderr=subprocess.PIPE, **kwargs).stdout

    adb = ["adb", "-s", args.device]
    remote = "/data/local/tmp/jimi-stt2-bench"
    stdlib = jar("org.jetbrains.kotlin", "kotlin-stdlib", "2.0.21")
    annotations = jar("org.jetbrains", "annotations", "13.0")
    compiler = [jar("org.jetbrains.kotlin", "kotlin-compiler-embeddable", "2.0.21"), stdlib,
                jar("org.jetbrains.kotlin", "kotlin-script-runtime", "2.0.21"),
                jar("org.jetbrains.kotlin", "kotlin-reflect", "1.6.10"),
                jar("org.jetbrains.intellij.deps", "trove4j", "1.0.20200330"),
                jar("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.6.4"), annotations]
    with zipfile.ZipFile(next((repo / "app/libs").glob("sherpa*.aar"))) as aar:
        (work / "sherpa.jar").write_bytes(aar.read("classes.jar"))
        native = work / "lib"
        native.mkdir(exist_ok=True)
        for name in aar.namelist():
            if name.startswith("jni/arm64-v8a/") and name.endswith(".so"):
                (native / Path(name).name).write_bytes(aar.read(name))
    android = str(sdk / "platforms/android-35/android.jar")
    cp = os.pathsep.join([stdlib, annotations, android, str(work / "sherpa.jar")])
    voice = repo / "app/src/main/java/com/alrasin/jarvis/voice"
    run([str(java_home / "bin/java"), "-Xmx384m", "-cp", os.pathsep.join(compiler),
         "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect", "-jvm-target", "17",
         "-classpath", cp, "-d", str(work / "runner.jar"),
         str(Path(__file__).with_name("PhoneAccuracy.kt")),
         str(voice / "SttVocabulary.kt"), str(voice / "SttCorrector.kt")])
    dex = work / "dex"
    dex.mkdir(exist_ok=True)
    run([str(java_home / "bin/java"), "-Xmx384m", "-cp", str(sdk / "build-tools/35.0.0/lib/d8.jar"),
         "com.android.tools.r8.D8", "--min-api", "30", "--lib", android,
         "--output", str(dex), str(work / "runner.jar"), str(work / "sherpa.jar"), stdlib])
    with zipfile.ZipFile(work / "runner-dex.jar", "w") as output:
        output.write(dex / "classes.dex", "classes.dex")
    baseline = json.loads((args.root / "kroko.json").read_text())
    selected = [row["file"] for row in baseline["rows"] if row["reference"] is not None]
    run(adb + ["shell", f"mkdir -p {remote}/commands {remote}/references {remote}/model {remote}/lib; chmod 700 {remote}"])
    for filename in selected:
        # Validate private metadata before constructing any host or remote paths.
        if Path(filename).name != filename or not filename.endswith(".wav"):
            parser.error("Invalid clip name")
        run(adb + ["push", str(args.root / "commands" / filename), remote + "/commands/"])
        run(adb + ["push", str(args.root / "references" / (Path(filename).stem + ".txt")), remote + "/references/"])
    for library in native.glob("*.so"):
        run(adb + ["push", str(library), remote + "/lib/"])
    run(adb + ["push", str(work / "runner-dex.jar"), remote + "/runner.jar"])
    script = ("apk=$(pm path com.alrasin.jarvis | cut -d: -f2); "
              f"for f in encoder.onnx decoder.onnx joiner.onnx tokens.txt; do "
              f"unzip -p \"$apk\" assets/stt/\"$f\" > {remote}/model/\"$f\" || exit 1; done; "
              f"LD_LIBRARY_PATH={remote}/lib CLASSPATH={remote}/runner.jar "
              f"app_process /system/bin com.alrasin.jarvis.eval.PhoneAccuracy {remote}")
    result = run(adb + ["shell", "su -c " + shlex.quote(script)])
    (work / "aggregate.txt").write_text(result)
    print(result.strip())


if __name__ == "__main__":
    try:
        main()
    except subprocess.CalledProcessError as failure:
        # Compiler/native diagnostics are private too; avoid dumping transcripts from providers.
        Path("/private/tmp/jimi-stt2-phone-error.log").write_text(failure.stderr or "")
        raise SystemExit("Phone benchmark failed; see /private/tmp/jimi-stt2-phone-error.log")

import tempfile
import os

extractor_script = """
allprojects { project ->
    tasks.register('kotlinLSPProjectDeps') { task ->
        doLast {
            sourceSets.forEach {
                it.java.srcDirs.forEach {
                    System.out.println "kotlin-lsp-sourcedir-java $it"
                }
                if (it.hasProperty("kotlin")) {
                    it.kotlin.srcDirs.forEach {
                        System.out.println "kotlin-lsp-sourcedir-kotlin $it"
                    }
                }
                it.compileClasspath.forEach {
                    System.out.println "kotlin-lsp-gradle $it"
                }
            }
        }
    }
}
"""

# Write the file to /tmp folder
tmp_file_path = ""
with tempfile.NamedTemporaryFile(delete=False) as tmp_file:
    tmp_file.write(extractor_script.encode())
    tmp_file_path = tmp_file.name

# Run gradlew command and capture stdout
gradle_output = os.popen(f"./gradlew -I {tmp_file_path} kotlinLSPProjectDeps --console=plain").read()

# Remove first 2 lines and last 3 lines
gradle_output = "\n".join(gradle_output.split("\n")[2:-3])

# Write output to .klsp-classpath file
with open(".klsp-classpath", "w") as klsp_classpath_file:
    klsp_classpath_file.write(gradle_output)

# Remove the file
os.remove(tmp_file_path)

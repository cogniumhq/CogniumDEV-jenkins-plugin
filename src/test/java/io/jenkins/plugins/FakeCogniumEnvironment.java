package io.jenkins.plugins;

import hudson.Functions;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

final class FakeCogniumEnvironment {

    private FakeCogniumEnvironment() {}

    static String script(boolean includeNpm, boolean isolateFromRealNpm, String extraEnvAssignments, String stepCall) {

        return scriptWithNodeVersion(includeNpm, isolateFromRealNpm, extraEnvAssignments, "20.19.0", stepCall);
    }

    static String scriptWithNodeVersion(
            boolean includeNpm, boolean isolatePath, String extraEnvAssignments, String nodeVersion, String stepCall) {

        String extra = "";

        if (extraEnvAssignments != null && !extraEnvAssignments.isBlank()) {
            extra = ", " + extraEnvAssignments;
        }

        String pathExpr = isolatePath
                ? "fake + sep + (isUnix() ? '/usr/bin:/bin' : (env.SystemRoot + '\\\\System32'))"
                : "fake + sep + env.PATH";

        StringBuilder groovy = new StringBuilder();

        groovy.append("node {\n");

        groovy.append("  def fake = \"${pwd()}/fake-bin\"\n");

        groovy.append("  def sep = isUnix() ? ':' : ';'\n");

        appendWriteFile(groovy, "fake-bin/node", unixNode(nodeVersion));

        appendWriteFile(groovy, "fake-bin/node.cmd", windowsNode(nodeVersion));

        appendWriteFile(groovy, "fake-bin/node.bat", windowsNode(nodeVersion));

        appendWriteFile(groovy, "fake-bin/cognium-dev-template", unixScan());

        appendWriteFile(groovy, "fake-bin/cognium-dev-template.cmd", windowsScan());

        if (includeNpm) {
            appendWriteFile(groovy, "fake-bin/npm", unixNpm());

            appendWriteFile(groovy, "fake-bin/npm.cmd", windowsNpm());
        }

        groovy.append("  if (isUnix()) { sh 'chmod -R a+x fake-bin' }\n");

        groovy.append("  withEnv([\"PATH=${").append(pathExpr).append("}\"");

        groovy.append(extra);

        groovy.append("]) {\n    ");

        groovy.append(stepCall);

        groovy.append("\n  }\n}\n");

        return groovy.toString();
    }

    private static void appendWriteFile(StringBuilder groovy, String path, String contents) {

        String encoded = Base64.getEncoder().encodeToString(contents.getBytes(StandardCharsets.UTF_8));

        groovy.append("  writeFile encoding: 'Base64', file: '")
                .append(path)
                .append("', text: '")
                .append(encoded)
                .append("'\n");
    }

    private static String unixNode(String version) {
        return "#!/bin/sh\n"
                + "if [ \"$1\" = \"-v\" ]; then\n"
                + "  echo \"" + version + "\"\n"
                + "  exit 0\n"
                + "fi\n"
                + "exit 0\n";
    }

    private static String windowsNode(String version) {
        return "@echo off\r\n"
                + "if \"%~1\"==\"-v\" (\r\n"
                + "  echo " + version + "\r\n"
                + "  exit /b 0\r\n"
                + ")\r\n"
                + "exit /b 0\r\n";
    }

    private static String unixScan() {
        return "#!/bin/sh\n"
                + "echo \"[fake-cognium-dev] $*\"\n"
                + "ec=${COGNIUM_TEST_EXIT_CODE:-0}\n"
                + "exit \"$ec\"\n";
    }

    private static String windowsScan() {
        return "@echo off\r\n"
                + "echo [fake-cognium-dev] %*\r\n"
                + "if \"%COGNIUM_TEST_EXIT_CODE%\"==\"\" exit /b 0\r\n"
                + "exit /b %COGNIUM_TEST_EXIT_CODE%\r\n";
    }

    private static String unixNpm() {
        return "#!/bin/sh\n"
                + "set -eu\n"
                + "echo \"[fake-npm] args: $*\"\n"
                + "echo \"[fake-npm] WORKSPACE=${WORKSPACE:-<unset>}\"\n"
                + "echo \"[fake-npm] PWD=$PWD\"\n"
                + "if [ \"${COGNIUM_TEST_NPM_FAIL:-}\" = \"1\" ]; then\n"
                + "  echo \"[fake-npm] failing install\" >&2\n"
                + "  exit 1\n"
                + "fi\n"
                + "prefix=\"\"\n"
                + "spec=\"\"\n"
                + "while [ \"$#\" -gt 0 ]; do\n"
                + "  case \"$1\" in cognium-dev@*) spec=\"${1#cognium-dev@}\" ;; esac\n"
                + "  if [ \"$1\" = \"--prefix\" ]; then\n"
                + "    prefix=\"$2\"\n"
                + "    shift 2\n"
                + "    continue\n"
                + "  fi\n"
                + "  shift\n"
                + "done\n"
                + "if [ -z \"$prefix\" ]; then\n"
                + "  echo \"[fake-npm] missing --prefix\" >&2\n"
                + "  exit 1\n"
                + "fi\n"
                + "src=\"${WORKSPACE:?WORKSPACE is not set}/fake-bin\"\n"
                + "echo \"[fake-npm] source=$src\"\n"
                + "echo \"[fake-npm] prefix=$prefix\"\n"
                + "/bin/ls -la \"$src\"\n"
                + "/bin/mkdir -p \"$prefix/node_modules/.bin\"\n"
                + "/bin/cp \"$src/cognium-dev-template\" \"$prefix/node_modules/.bin/cognium-dev\"\n"
                + "/bin/chmod +x \"$prefix/node_modules/.bin/cognium-dev\"\n"
                + "test -x \"$prefix/node_modules/.bin/cognium-dev\"\n"
                + "version=\"${COGNIUM_TEST_INSTALLED_VERSION:-$spec}\"\n"
                + "/bin/mkdir -p \"$prefix/node_modules/cognium-dev\"\n"
                + "printf '{\"name\":\"cognium-dev\",\"version\":\"%s\"}\\n' \"$version\""
                + " > \"$prefix/node_modules/cognium-dev/package.json\"\n"
                + "echo \"[fake-npm] installed into $prefix\"\n";
    }

    private static String windowsNpm() {
        return "@echo off\r\n"
                + "setlocal EnableExtensions\r\n"
                + "if \"%COGNIUM_TEST_NPM_FAIL%\"==\"1\" (\r\n"
                + "  echo [fake-npm] failing install 1>&2\r\n"
                + "  exit /b 1\r\n"
                + ")\r\n"
                + "echo [fake-npm] args: %*\r\n"
                + "set \"PREFIX=\"\r\n"
                + "set \"SPEC=\"\r\n"
                + ":parse\r\n"
                + "if \"%~1\"==\"\" goto done\r\n"
                + "set \"ARG=%~1\"\r\n"
                + "if \"%ARG:~0,12%\"==\"cognium-dev@\" set \"SPEC=%ARG:~12%\"\r\n"
                + "if /I \"%~1\"==\"--prefix\" (\r\n"
                + "  set \"PREFIX=%~2\"\r\n"
                + "  shift\r\n"
                + "  shift\r\n"
                + "  goto parse\r\n"
                + ")\r\n"
                + "shift\r\n"
                + "goto parse\r\n"
                + ":done\r\n"
                + "if \"%PREFIX%\"==\"\" (\r\n"
                + "  echo [fake-npm] missing --prefix 1>&2\r\n"
                + "  exit /b 1\r\n"
                + ")\r\n"
                + "mkdir \"%PREFIX%\\node_modules\\.bin\"\r\n"
                + "copy /Y \"%WORKSPACE%\\fake-bin\\cognium-dev-template\" "
                + "\"%PREFIX%\\node_modules\\.bin\\cognium-dev\"\r\n"
                + "if errorlevel 1 exit /b 1\r\n"
                + "copy /Y \"%WORKSPACE%\\fake-bin\\cognium-dev-template.cmd\" "
                + "\"%PREFIX%\\node_modules\\.bin\\cognium-dev.cmd\"\r\n"
                + "if errorlevel 1 exit /b 1\r\n"
                + "if not exist \"%PREFIX%\\node_modules\\.bin\\cognium-dev.cmd\" "
                + "exit /b 1\r\n"
                + "if defined COGNIUM_TEST_INSTALLED_VERSION set \"SPEC=%COGNIUM_TEST_INSTALLED_VERSION%\"\r\n"
                + "mkdir \"%PREFIX%\\node_modules\\cognium-dev\"\r\n"
                + ">\"%PREFIX%\\node_modules\\cognium-dev\\package.json\" "
                + "echo {\"name\":\"cognium-dev\",\"version\":\"%SPEC%\"}\r\n"
                + "echo [fake-npm] installed into %PREFIX%\r\n"
                + "exit /b 0\r\n";
    }

    static boolean isWindowsAgent() {
        return Functions.isWindows() || File.separatorChar == '\\';
    }

    static String platformLabel() {
        return isWindowsAgent() ? "windows" : "unix";
    }

    static String expectedNpmName() {
        return CogniumCli.npmExecutable(!isWindowsAgent());
    }
}

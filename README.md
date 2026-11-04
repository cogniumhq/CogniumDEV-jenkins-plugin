# Cognium SAST Jenkins Plugin

Jenkins Pipeline plugin that runs [Cognium](https://cognium.dev) static application security testing against the workspace.

Cognium is a SAST tool for finding security issues in source code, including AI-written code. This plugin installs a pinned `cognium-dev` release into a temporary directory on the Jenkins **agent** (not globally, with npm install scripts disabled) and runs `cognium-dev scan`.

This plugin is **not** published to the Jenkins update center yet.

## Example Pipeline

```groovy
pipeline {
    agent any

    stages {
        stage('Cognium SAST') {
            steps {
                cognium(
                    path: '.',
                    packageVersion: '4.17.0',
                    format: 'text',
                    failOnFindings: false
                )
            }
        }
    }
}
```

### Blocking security gate

By default, security findings are logged and the build continues. To fail the step when Cognium reports security findings:

```groovy
cognium(
    path: '.',
    packageVersion: '4.17.0',
    failOnFindings: true
)
```

### Scanner version and install scripts

The plugin installs an exact `cognium-dev` release, never whatever npm published last:

- **Default:** `4.17.0` (`CogniumCli.DEFAULT_PACKAGE_VERSION`). A plugin release changes it deliberately.
- **Configurable:** set `packageVersion` to another exact version, for example `packageVersion: '4.18.1'`.
- **Verified:** after the install the step reads `node_modules/cognium-dev/package.json` and fails if an exact `packageVersion` does not match the installed version.
- **No install scripts:** npm runs with `--ignore-scripts`, so no `preinstall`, `install` or `postinstall` script of `cognium-dev` or its dependencies executes on the agent. `cognium-dev` and its dependencies (`circle-ir`, `web-tree-sitter`, `yaml`, `@cognium/project-profile-detect`) declare no install-time scripts and do not need any. If a future release needs one, that release has to be documented here before the plugin default moves to it.

A dist-tag or range (`latest`, `4.x`) is still accepted, with a warning in the log and in the form check. It installs and then runs the current npm release on every build, so a new release reaches your agents without review. Use it only if that is what you want.

## Parameters

| Parameter        | Default  | Description                                                                                                                                                  |
| ---------------- | -------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `path`           | `.`      | Path passed to `cognium-dev scan` (relative to the workspace).                                                                                               |
| `packageVersion` | `4.17.0` | Exact `cognium-dev` version to install (checked after install). A dist-tag such as `latest` installs the current npm release on every build and logs a warning. |
| `format`         | `text`   | `--format` (`text`, `json`, or `sarif`).                                                                                                                     |
| `output`         | unset    | Optional `--output` report path.                                                                                                                             |
| `severity`       | unset    | Optional `--severity` filter (for example `high` or `critical,high`).                                                                                        |
| `category`       | unset    | Optional `--category` filter (for example `security`).                                                                                                       |
| `failOnFindings` | `false`  | When `true`, security findings fail the step.                                                                                                                |

All parameters are optional. The Pipeline function name is `cognium`.

## Build result behavior

| Situation                                 | `failOnFindings: false` (default) | `failOnFindings: true` |
| ----------------------------------------- | --------------------------------- | ---------------------- |
| Scan exit code `0` (no security findings) | Success                           | Success                |
| Scan exit code `1` (security findings)    | Success (findings logged)         | Failure                |
| Any other non-zero scan exit code         | Failure                           | Failure                |
| Node.js / npm missing                     | Failure                           | Failure                |
| `cognium-dev` install failure             | Failure                           | Failure                |

This matches the Cognium Buildkite plugin: findings do not fail the job unless you opt in, while installation and operational errors always fail.

`cognium-dev` currently documents exit code `0` as no security findings, `1` as security findings, and `2` as an analysis error. That mapping is isolated in the plugin so it can be updated if the CLI contract changes.

## Requirements

- Jenkins 2.528.3 or newer (plugin baseline 2.528). Official Jenkins runtimes for this line are Java 17 or 21. The plugin does **not** require Java 25.
- A Jenkins agent with Node.js and npm on `PATH`.
- Node.js **>= 20.19.0**, which is what current `cognium-dev` releases need.
- Network access from the agent to the npm registry at scan time (the plugin installs `cognium-dev` into a temporary directory).

The plugin does not use Jenkins credentials. Source is scanned on the agent; this plugin does not send code to Cognium.

## How it works

1. The `cognium` step runs on the agent that owns the workspace.
2. It checks that Node.js is on `PATH`.
3. It installs `cognium-dev@<packageVersion>` into a temporary directory with `npm install --prefix <dir> --no-save --ignore-scripts` (not a global install, no install scripts). The default is the exact version `4.17.0`.
4. It reads the installed `package.json` and fails if an exact `packageVersion` was not what npm installed.
5. It runs `cognium-dev scan <path> --format ...` from that install (Windows uses `npm.cmd` / `cognium-dev.cmd`).
6. It deletes the temporary directory even when the scan fails.

## Development

Build with Java 17 or 21 and Maven 3.9+ (Maven 3.10 is fine). Do not treat Java 25 as a plugin requirement; Jenkins 2.528 is not fully supported on Java 25.

```bash
mvn spotless:apply
mvn verify
```

`mvn verify` compiles the plugin, runs Spotless, SpotBugs, and the Jenkins test harness.

Optional local UI:

```bash
mvn hpi:run
```

`hpi:run` is **not** the supported way to validate this plugin. Pipeline test plugins are test-scoped, so the local Jenkins UI may not expose Pipeline jobs or `/pipeline-syntax/`. Use `mvn verify` instead.

### Tests

Tests do **not** download `cognium-dev` from npm. They put fake `node` / `npm` executables on `PATH` and a fake `cognium-dev` that honors `COGNIUM_TEST_EXIT_CODE`. The fake npm writes `node_modules/cognium-dev/package.json` with the requested version, or with `COGNIUM_TEST_INSTALLED_VERSION` to test a mismatch.

Covered behavior includes descriptor registration, defaults, Pipeline invocation, install failure, successful scan, findings with and without `failOnFindings`, operational errors, temporary directory cleanup, and Windows vs Unix command selection.

## Jenkins hosting

This plugin is not in the Jenkins update center yet. Hosting follows the Jenkins documentation:

- [Request hosting](https://www.jenkins.io/doc/developer/publishing/requesting-hosting/)
- Plugin ID / `artifactId`: `cognium`
- After adoption the GitHub repository is typically `jenkinsci/cognium-plugin`

Until then, keep `<gitHubRepo>` in `pom.xml` pointed at the current repository. Do not set it to `jenkinsci/...` until the Jenkins project actually hosts it.

## License

MIT. See [LICENSE.md](LICENSE.md).
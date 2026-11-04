# Cognium SAST Jenkins Plugin

Jenkins Pipeline plugin that runs [Cognium](https://cognium.dev) static application security testing against the workspace.

Cognium is a SAST tool for finding security issues in source code, including AI-written code. This plugin installs `cognium-dev` into a temporary directory on the Jenkins **agent** (not globally) and runs `cognium-dev scan`.

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
    failOnFindings: true
)
```

### Pin a scanner version

By default the plugin installs `cognium-dev@latest` from npm, so new CLI releases are picked up automatically. Pin a version when a pipeline must stay on one scanner:

```groovy
cognium(
    path: '.',
    packageVersion: '4.14.2',
    failOnFindings: true
)
```

## Parameters

| Parameter        | Default  | Description                                                                                                                                                  |
| ---------------- | -------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `path`           | `.`      | Path passed to `cognium-dev scan`, relative to the workspace. See [Workspace confinement](#workspace-confinement).                                            |
| `packageVersion` | `latest` | npm version or dist-tag of `cognium-dev`. `latest` always installs the current npm release. Pin a version (for example `4.14.2`) for a reproducible scanner. |
| `format`         | `text`   | `--format` (`text`, `json`, or `sarif`).                                                                                                                     |
| `output`         | unset    | Optional `--output` report path, relative to the workspace. See [Workspace confinement](#workspace-confinement).                                             |
| `severity`       | unset    | Optional `--severity` filter (for example `high` or `critical,high`).                                                                                        |
| `category`       | unset    | Optional `--category` filter (for example `security`).                                                                                                       |
| `failOnFindings` | `false`  | When `true`, security findings fail the step.                                                                                                                |

All parameters are optional. The Pipeline function name is `cognium`.

### Workspace confinement

`path` and `output` must stay inside the job workspace. The step fails, before anything is installed, when either:

- is absolute (`/etc`, `C:\x`, `\\server\share`) or contains a `..` segment;
- starts with `-` (it would be read as a CLI option);
- contains `:`, a control character or one of `" & | < > ^ % ! `` ` `` (on Windows these are arguments to `cognium-dev.cmd`, which runs through `cmd.exe`);
- resolves, on the agent, through a symbolic link that points outside the workspace, or through a dangling symbolic link.

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
3. It installs `cognium-dev@<packageVersion>` into a temporary directory with `npm install --prefix` (not a global install). The default tag is `latest`.
4. It runs `cognium-dev scan <path> --format ...` from that install (Windows uses `npm.cmd` / `cognium-dev.cmd`).
5. It deletes the temporary directory even when the scan fails.

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

Tests do **not** download `cognium-dev` from npm. They put fake `node` / `npm` executables on `PATH` and a fake `cognium-dev` that honors `COGNIUM_TEST_EXIT_CODE`.

Covered behavior includes descriptor registration, defaults, Pipeline invocation, install failure, successful scan, findings with and without `failOnFindings`, operational errors, temporary directory cleanup, and Windows vs Unix command selection.

## Jenkins hosting

This plugin is not in the Jenkins update center yet. Hosting follows the Jenkins documentation:

- [Request hosting](https://www.jenkins.io/doc/developer/publishing/requesting-hosting/)
- Plugin ID / `artifactId`: `cognium`
- After adoption the GitHub repository is typically `jenkinsci/cognium-plugin`

Until then, keep `<gitHubRepo>` in `pom.xml` pointed at the current repository. Do not set it to `jenkinsci/...` until the Jenkins project actually hosts it.

## License

MIT. See [LICENSE.md](LICENSE.md).
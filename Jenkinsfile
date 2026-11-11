/*
 * Cognium SAST Jenkins Plugin CI
 *
 * Tests the plugin on Linux and Windows using Java 21.
 * Java 21 is compatible with the plugin's Jenkins baseline.
 */

buildPlugin(
    forkCount: '1C',
    useContainerAgent: true,
    configurations: [
        [platform: 'linux', jdk: 25],
        [platform: 'windows', jdk: 21]
    ]
)
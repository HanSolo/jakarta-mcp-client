# Releasing

Nothing in this repository uploads to Maven Central by itself. The last step is always yours.

1. Wait for a green CI run on the commit you want to release.
2. Tag it and push the tag (the poms keep `0.1.0-SNAPSHOT`; the tag sets the version for the build):
   ```
   git tag v0.1.0 && git push origin v0.1.0
   ```
3. The `Release (bundle only)` workflow runs the tests, builds `mcp-jakarta-client` and `mcp-jakarta-client-cdi` with sources, javadoc and GPG signatures, checks
   that every jar and pom has a signature and checksums, and attaches `mcp-jakarta-client-<version>-central-bundle.zip` to a GitHub **pre-release**.
4. Download the bundle and upload it at <https://central.sonatype.com/publishing> (Publish Component). Wait for the validation, look at the files, then press **Publish**.
   Publishing cannot be undone.
5. Edit the GitHub pre-release into a release once the artifacts show up on Central.

Needs the repository secrets `GPG_PRIVATE_KEY` (armored, `gpg --armor --export-secret-keys <id>`) and `GPG_PASSPHRASE`, and a verified `eu.hansolo.mcp` namespace in the portal.

A dry run without signing or tag:
```
mvn -Prelease -Dgpg.skip=true -DskipTests verify
(cd cdi && mvn -Prelease -Dgpg.skip=true -DskipTests verify)
```

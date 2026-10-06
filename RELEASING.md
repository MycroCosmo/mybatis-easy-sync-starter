# 릴리스 절차 (Maven Central)

Maven 좌표는 `io.github.mycrocosmo:mybatis-easy-core` / `io.github.mycrocosmo:mybatis-easy-processor` 입니다.
Java 패키지명(`com.thenoah.dev...`)은 Maven groupId와 별개이며 변경하지 않았습니다.

## 1회성 준비 (저장소 소유자만 가능)

1. **Central Portal 계정과 네임스페이스 인증**
   - https://central.sonatype.com 에 GitHub 계정으로 가입합니다.
   - Namespaces에서 `io.github.mycrocosmo`를 등록하고 안내에 따라 인증합니다
     (Portal이 알려주는 임시 이름의 공개 저장소를 GitHub 계정에 만드는 방식).
2. **Portal 사용자 토큰 발급**: Account → *Generate User Token*. 발급된 username/password를 시크릿에 넣습니다.
3. **GPG 키 생성 및 공개키 배포**
   ```bash
   gpg --full-generate-key                      # RSA 4096 권장
   gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>
   gpg --armor --export-secret-keys <KEY_ID>    # SIGNING_KEY 값 (-----BEGIN ... 전체)
   ```
4. **GitHub Secrets 등록** (Settings → Secrets and variables → Actions)

   | 시크릿 | 값 |
   | --- | --- |
   | `MAVEN_CENTRAL_USERNAME` | Portal 사용자 토큰 username |
   | `MAVEN_CENTRAL_PASSWORD` | Portal 사용자 토큰 password |
   | `SIGNING_KEY` | `gpg --armor --export-secret-keys` 출력 전체 |
   | `SIGNING_KEY_ID` | 키 ID 마지막 8자리 |
   | `SIGNING_KEY_PASSWORD` | 키 패스프레이즈 |

## 릴리스

1. 루트 `build.gradle`의 `version`을 올리고 README의 좌표를 맞춥니다.
2. `main`에 머지한 뒤 같은 버전으로 태그를 푸시합니다.
   ```bash
   git tag v1.0.3
   git push origin v1.0.3
   ```
3. `Release` 워크플로가 태그와 `build.gradle`의 버전이 같은지 확인하고, 빌드·테스트 후 Central Portal에 **업로드**합니다.
4. https://central.sonatype.com/publishing/deployments 에서 검증 결과를 확인하고 **Publish**를 누릅니다.
   Central에 한 번 공개한 버전은 삭제할 수 없으므로 첫 릴리스는 반드시 직접 확인한 뒤 공개하세요.
   (익숙해진 뒤 자동 공개로 바꾸려면 워크플로의 `publishToMavenCentral`을 `publishAndReleaseToMavenCentral`로 바꿉니다.)

## 로컬 확인

```bash
./gradlew publishToMavenLocal   # 서명 키 없이 동작. sources/javadoc JAR와 POM이 생성됨
```

서명은 `signingInMemoryKey` 프로퍼티가 있을 때만 수행됩니다.

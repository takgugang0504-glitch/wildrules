Wild Rules 모드 (Fabric 26.3) - 소스 프로젝트 (zip 이름만 jar 로 바꾸면 동작하지 않습니다)
방법 A (설치 없이): GitHub 에 새 저장소를 만들고 이 폴더의 파일 전부(.github 폴더 포함)를 업로드
  -> Actions 탭에서 build 성공 확인 -> Artifacts 의 wildrules-jar 다운로드 -> 압축 풀어 나온 jar 를 mods 폴더에
방법 B: JDK 25 + IntelliJ 로 폴더 열고 Gradle build -> build/libs/wildrules-0.1.0.jar
설치: mods 폴더에 jar + Fabric API 0.163.0+26.3

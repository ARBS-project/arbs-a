# NOTICE — ARBS Moonlight (포크)

이 앱은 **GPL-3.0** 으로 배포되는 Moonlight Android 의 수정본이다. 원본 `LICENSE.txt`(GPL-3.0)를 그대로 유지한다.

## 출처
- moonlight-stream/moonlight-android `b48494cb96bff23d8886c4775cc4f39a1075495d` (2026-09-12, master)
- `app/src/main/jni/moonlight-core/moonlight-common-c/` 는 서브모듈이 아니라 **Nonary/moonlight-common-c `6f05ee8947d1b2d7b69fd4983029d0447e521997`**(2026-11-17, GPL-3.0)의 작업 트리를 그대로 포함한다.
  - enet `98dbb897ac4a7208038e0782cddedf89dcf0723a`, nanors `a9b1636f9c98dbfdd0daeaf6982ccd24d1c30f29` 포함. `.gitmodules` 는 삭제했다.
- 정적 라이브러리(openssl, opus)는 원본 저장소가 포함한 것을 그대로 사용(출처: cgutman/moonlight-mobile-deps).

## 수정 사실 (ARBS, 2026-11-18, A1-1)
- 패키지(앱 ID) `dev.arbs.moonlight`, 앱 이름 "ARBS Moonlight" (debug 접미사 제거). Java 패키지명(`com.limelight`)은 유지.
- `callbacks.c`, `MoonBridge.java`: `controllerHaptics`·`setAdaptiveTriggers` 콜백 연결(로그만).
- 신규 `com.limelight.arbs.*`(ArbsLog, ArbsFeedbackLog, ArbsLogActivity): 수신 로그 기록·표시·저장.
- `ControllerHandler.java`: 컨트롤러 도착(type/capability) 로그, 시험 옵션(기본 꺼짐) 시 PS 타입+`LI_CCAP_HAPTICS_PCM` 보고.
- `PreferenceConfiguration`, `preferences.xml`, `strings.xml`, `StreamSettings`, `AndroidManifest.xml`, `app/build.gradle`: 설정 항목·화면·ID 변경.
- 변경 내역 전체는 이 폴더의 로컬 git 이력(`git log`, 기준 커밋 = 위 출처 두 개를 합친 baseline)으로 확인할 수 있다.

## 배포 시 의무 요약
GPL-3.0: 동일 라이선스로 대응 소스 제공, 저작권·라이선스 고지 유지, 수정 표시, 추가 제한 금지. 현재는 개인 시험용이며 배포하지 않는다.

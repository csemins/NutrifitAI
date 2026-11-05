@echo off
rem NutriFit AI server (Java): AI proxy + private image bucket on http://127.0.0.1:8787
rem Reads server\.env (NUTRIFIT_LLM_API_KEY, FIREBASE_PROJECT_ID, UPSTREAM_BASE_URL ...).
cd /d "%~dp0"
if not exist build\libs\nutrifitai-server.jar (
  call ..\gradlew.bat -p . fatJar -q || exit /b 1
)
java -jar build\libs\nutrifitai-server.jar

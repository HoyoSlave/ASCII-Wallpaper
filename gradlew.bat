@rem Gradle wrapper script for Windows
@echo off
if exist "%~dp0gradle\wrapper\gradle-wrapper.jar" (
    java -Xmx64m -Xms64m -jar "%~dp0gradle\wrapper\gradle-wrapper.jar" %*
) else (
    gradle %*
)

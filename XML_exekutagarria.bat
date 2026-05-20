@echo off
setlocal

cd /d "%~dp0"

if not exist "target\scheduler" (
    mkdir "target\scheduler"
)

javac -d "target\scheduler" "src\main\java\org\example\DeskargatuXML.java" "src\main\java\org\example\Main.java"
if errorlevel 1 (
    echo Error al compilar Taldea1_Java_XML.
    exit /b 1
)

java -cp "target\scheduler" org.example.Main
if errorlevel 1 (
    echo Error al ejecutar Taldea1_Java_XML.
    exit /b 1
)

endlocal

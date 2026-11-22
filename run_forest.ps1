# Compiles the engine and starts it with the forest scene
# Run from anywhere:  .\run_forest.ps1

$scene = "forest"

Set-Location $PSScriptRoot

New-Item -ItemType Directory -Force "out\run" | Out-Null
Get-ChildItem -Recurse "src" -Filter "*.java" |
    ForEach-Object { '"' + $_.FullName.Replace('\', '/') + '"' } |
    Set-Content -Encoding ascii "out\run\sources.txt"
javac -nowarn -d "out\run" -cp "libs/jars/*" "@out\run\sources.txt"
if ($LASTEXITCODE -ne 0) { Write-Host "Compilation failed."; exit 1 }

java "-Djava.library.path=libs/natives" -cp "out/run;src;res;libs/jars/*" engineTester.MainGameLoop $scene @args

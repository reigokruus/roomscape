@echo off
rem Option 1: photo-textured mesh. Usage: drag a dataset folder onto this file,
rem or run-1-textured-mesh.cmd DATASET_FOLDER. Result: DATASET_FOLDER-mesh\room.glb
cd /d "%~dp0"
call .venv\Scripts\activate.bat
set DS=%~1
if "%DS%"=="" set /p DS=Dataset folder (has transforms.json): 
python textured_mesh.py "%DS%" "%DS%-mesh" %2 %3 %4 %5 %6
start "" "%DS%-mesh"
pause

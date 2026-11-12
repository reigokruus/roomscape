@echo off
rem Option 2: depth-guided flat splats. Usage: run-2-flat-splats.cmd DATASET_FOLDER
rem Result: DATASET_FOLDER-flat\splats.ply (open in Brush) and mesh.ply
cd /d "%~dp0"
call .venv\Scripts\activate.bat
set DS=%~1
if "%DS%"=="" set /p DS=Dataset folder (has transforms.json): 
python flat_splats.py "%DS%" "%DS%-flat" %2 %3 %4 %5 %6
start "" "%DS%-flat"
pause

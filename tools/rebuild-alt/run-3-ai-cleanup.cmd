@echo off
rem Option 3: AI clean-up (Difix3D+, research licence). Usage:
rem   run-3-ai-cleanup.cmd DATASET_FOLDER SPLATS.ply
rem Result: DATASET_FOLDER-ai\before_after\*.png, novel_before_after.mp4, dataset_plus\
cd /d "%~dp0"
call .venv\Scripts\activate.bat
set DS=%~1
set SP=%~2
if "%DS%"=="" set /p DS=Dataset folder (has transforms.json): 
if "%SP%"=="" set /p SP=Splat file (.ply from Brush): 
python ai_cleanup.py "%DS%" "%SP%" "%DS%-ai" --difix "%~dp0Difix3D" %3 %4 %5 %6
start "" "%DS%-ai"
pause

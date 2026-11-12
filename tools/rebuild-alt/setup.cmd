@echo off
rem One-time setup on a Windows PC with an NVIDIA GPU (LEGION). Makes .venv here,
rem installs the three tools' packages and fetches Difix3D (research licence).
cd /d "%~dp0"
where py >nul 2>nul || (echo Python launcher "py" not found. Install Python 3.12 from python.org first. & pause & exit /b 1)
if not exist .venv py -3.12 -m venv .venv || py -3 -m venv .venv
call .venv\Scripts\activate.bat
python -m pip install --upgrade pip
pip install -r requirements-mesh.txt || goto :fail
pip install torch --index-url https://download.pytorch.org/whl/cu124 || goto :fail
pip install -r requirements-gpu.txt || goto :fail
if not exist Difix3D git clone https://github.com/nv-tlabs/Difix3D.git && git -C Difix3D checkout c76edc595586e16732c91ddee82f3a6d83a8a9cc
python -c "import torch; print('CUDA ok:', torch.cuda.is_available(), torch.cuda.get_device_name(0) if torch.cuda.is_available() else '')"
echo Setup done.
pause
exit /b 0
:fail
echo Setup failed, see the messages above.
pause
exit /b 1

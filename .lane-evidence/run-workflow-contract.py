import runpy
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent / 'python-deps'))
sys.argv = ['.github/scripts/test_kubo_workflows.py', '--contract-only']
runpy.run_path(sys.argv[0], run_name='__main__')

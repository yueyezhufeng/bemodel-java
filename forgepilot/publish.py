"""Publish generated model + browser UI. Optional explicit existing ForgePilot target."""
from pathlib import Path
import argparse
import shutil
from generate import generate

ROOT = Path(__file__).resolve().parent

def copy_ui(destination):
    destination.mkdir(parents=True,exist_ok=True)
    shutil.copytree(ROOT/'web',destination,dirs_exist_ok=True)
    shutil.copy2(ROOT/'catalog.json',destination/'catalog.json')

if __name__=='__main__':
    parser=argparse.ArgumentParser()
    parser.add_argument('--forgepilot',type=Path,help='Existing ForgePilot repository root')
    args=parser.parse_args()
    generate()
    copy_ui(ROOT.parent/'bemodel-web/public/industrial-foundation')
    if args.forgepilot:
        demo=args.forgepilot.resolve()/'demo'
        if not (demo/'app/ontology.py').is_file():
            raise SystemExit('Target must contain the ForgePilot ontology adapter; refusing unknown workspace')
        copy_ui(demo/'ontology')
        copy_ui(demo/'web/public/ontology')
    print('Published model and static industry UI')

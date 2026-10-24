"""Local-only industrial demo launcher. Credentials stay in ignored .runtime/."""
from pathlib import Path
import argparse
import os
import secrets
import signal
import subprocess
import time
import urllib.request
import urllib.error

ROOT=Path(__file__).resolve().parent.parent
RUNTIME=ROOT/'.runtime'
CONTAINER='forgepilot-ontology-mysql'
IMAGE='public.ecr.aws/docker/library/mysql:8.4'

def credentials():
    RUNTIME.mkdir(exist_ok=True)
    path=RUNTIME/'mysql.env'
    if not path.exists():
        path.write_text('MYSQL_ROOT_PASSWORD='+secrets.token_urlsafe(32)+'\nMYSQL_USER=forgepilot\n'
            'MYSQL_USERNAME=forgepilot\nMYSQL_PASSWORD='+secrets.token_urlsafe(32)+'\nMYSQL_DATABASE=forgepilot_industrial\n')
        path.chmod(0o600)
    values=dict(line.split('=',1) for line in path.read_text().splitlines() if '=' in line)
    app=RUNTIME/'app.env'
    if not app.exists():
        app.write_text('JWT_SECRET='+secrets.token_urlsafe(48)+'\nAPP_SECRET_KEY='+secrets.token_urlsafe(32)+'\n')
        app.chmod(0o600)
    values.update(dict(line.split('=',1) for line in app.read_text().splitlines() if '=' in line))
    return values

def init_db():
    credentials()
    exists=subprocess.run(['docker','inspect',CONTAINER],capture_output=True).returncode==0
    if exists:
        subprocess.run(['docker','start',CONTAINER],check=True,stdout=subprocess.DEVNULL)
    else:
        subprocess.run(['docker','run','-d','--name',CONTAINER,'--env-file',str(RUNTIME/'mysql.env'),
            '-p','127.0.0.1:13306:3306','--mount','type=volume,source=forgepilot_ontology_mysql,target=/var/lib/mysql',IMAGE],check=True)
    for _ in range(45):
        ready=subprocess.run(['docker','exec',CONTAINER,'mysqladmin','ping','-h','127.0.0.1','--silent'],capture_output=True)
        if ready.returncode==0:
            print('Industrial MySQL ready on 127.0.0.1:13306');return
        time.sleep(1)
    raise SystemExit('Database not ready; inspect container logs. Existing data retained.')

def start(java):
    values=credentials()
    jar=ROOT/'bemodel-server/target/bemodel-server-1.0.0.jar'
    if not jar.exists(): raise SystemExit('Build the Java jar first; see forgepilot/README.md')
    pid_file=RUNTIME/'backend.pid'
    if pid_file.exists():
        try: os.kill(int(pid_file.read_text()),0)
        except ProcessLookupError: pass
        else: raise SystemExit('Recorded backend process is still alive; stop it before restarting.')
    env={**os.environ,**values,'SPRING_PROFILES_ACTIVE':'forgepilot','DEEPSEEK_API_KEY':'','DEEPSEEK_BACKUP_API_KEY':''}
    with (RUNTIME/'backend.log').open('ab') as log:
        child=subprocess.Popen([java,'-jar',str(jar)],cwd=ROOT/'bemodel-server',env=env,
            stdout=log,stderr=log,start_new_session=True)
    pid_file.write_text(str(child.pid)+'\n')
    for _ in range(40):
        if child.poll() is not None:
            raise SystemExit('Industrial startup failed; inspect .runtime/backend.log (credentials are not printed here)')
        try:
            with urllib.request.urlopen('http://127.0.0.1:18081/login',timeout=1) as response:
                if response.status==200:
                    print('Industrial Java service ready: http://127.0.0.1:18081 ; log: .runtime/backend.log')
                    return
        except (urllib.error.URLError,TimeoutError): pass
        time.sleep(.5)
    raise SystemExit('Startup is still pending; inspect .runtime/backend.log. Process and database retained.')

def stop():
    path=RUNTIME/'backend.pid'
    if not path.exists(): return
    pid=int(path.read_text())
    try:
        command=subprocess.check_output(['ps','-p',str(pid),'-o','command='],text=True)
        if str(ROOT/'bemodel-server/target/bemodel-server-1.0.0.jar') not in command:
            raise SystemExit('PID no longer belongs to this demo; refusing to stop it')
        os.kill(pid,signal.SIGTERM)
        for _ in range(100):
            try: os.kill(pid,0)
            except ProcessLookupError: break
            time.sleep(.1)
        else: raise SystemExit('Service is still stopping; PID retained, retry after checking its status.')
    except subprocess.CalledProcessError: pass
    path.unlink(missing_ok=True)
    print('Stopped industrial Java service; database retained')

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('action',choices=['init-db','start','stop'])
    p.add_argument('--java',default='java');args=p.parse_args()
    if args.action=='init-db': init_db()
    elif args.action=='start': start(args.java)
    else: stop()

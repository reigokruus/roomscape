#!/usr/bin/env python3
"""Upload a finished room to its share link (roomscape-web, docs/share-links.md there), from the LEGION or the MAC.

  python upload_room.py --id <room id> --ply splat_30000.ply [--title "Living room"] [--base https://roomscape.eu]
  python upload_room.py --id <room id> --splat big.splat --version full        (an existing light copy as it is)
  python upload_room.py --id <room id> --delete

The room id is the phone's (it made the link after its preview: share.json in the "For a PC rebuild" zip), so the
full version lands on the link people already have. From a .ply it makes two light copies with ply2splat.py: "full"
(at most 1.5 M splats, laptops and workstations) and "full-phone" (at most 400k, phone browsers), and uploads both.
The upload token comes from the ROOMSCAPE_UPLOAD_TOKEN environment variable or --token-file (never on the command
line, never in git). Standard library only; files over 90 MB go up in 90 MB parts; each request is retried 4 times.
"""
import argparse, json, os, subprocess, sys, tempfile, time, urllib.request, urllib.error

PART = 90 * 1024 * 1024
HERE = os.path.dirname(os.path.abspath(__file__))


def request(method, url, token, data=None, headers=None, tries=5):
    h = {'Authorization': f'Bearer {token}', 'User-Agent': 'roomscape-upload'}
    h.update(headers or {})
    for attempt in range(tries):
        try:
            req = urllib.request.Request(url, data=data, method=method, headers=h)
            with urllib.request.urlopen(req, timeout=600) as r:
                body = r.read()
                return json.loads(body) if body else {}
        except urllib.error.HTTPError as e:
            if e.code < 500 or attempt == tries - 1:
                sys.exit(f'{method} {url}: {e.code} {e.read().decode(errors="replace")}')
        except (urllib.error.URLError, TimeoutError, ConnectionError) as e:
            if attempt == tries - 1: sys.exit(f'{method} {url}: {e}')
        time.sleep(2 ** (attempt + 1))


def upload(base, token, room, version, path, extra):
    fmt = os.path.splitext(path)[1].lstrip('.').lower()
    size = os.path.getsize(path)
    url = f'{base}/api/rooms/{room}/{version}'
    print(f'{version}: {path} ({size / 1e6:.1f} MB)')
    if size <= PART:
        with open(path, 'rb') as f:
            return request('PUT', f'{url}?format={fmt}', token, f.read(), {**extra, 'Content-Length': str(size)})
    up = request('POST', f'{url}/uploads?format={fmt}', token, b'')['uploadId']
    parts = []
    with open(path, 'rb') as f:
        n = 1
        while chunk := f.read(PART):
            r = request('PUT', f'{url}/uploads/{up}/{n}?format={fmt}', token, chunk, {'Content-Length': str(len(chunk))})
            parts.append({'partNumber': r['partNumber'], 'etag': r['etag']})
            print(f'  part {n}: {f.tell() / size:.0%}')
            n += 1
    return request('POST', f'{url}/uploads/{up}/complete?format={fmt}', token,
                   json.dumps({'parts': parts}).encode(), {**extra, 'Content-Type': 'application/json'})


def splat_count(path):
    return os.path.getsize(path) // 32 if path.endswith('.splat') else None


def main():
    a = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    a.add_argument('--id', required=True, help='the room id from the phone (22 characters)')
    a.add_argument('--base', default='https://roomscape.eu')
    a.add_argument('--ply', help='trained splat (.ply): makes and uploads the full and full-phone light copies')
    a.add_argument('--splat', help='an existing .splat/.ply/.ksplat to upload as it is (with --version)')
    a.add_argument('--version', default='full', choices=['preview', 'full-phone', 'full'])
    a.add_argument('--title'); a.add_argument('--view', help='start view JSON {"cam":[x,y,z],"look":[x,y,z]}')
    a.add_argument('--token-file'); a.add_argument('--delete', action='store_true')
    o = a.parse_args()
    token = os.environ.get('ROOMSCAPE_UPLOAD_TOKEN') or (open(o.token_file).read().strip() if o.token_file else '')
    if not token: sys.exit('No upload token: set ROOMSCAPE_UPLOAD_TOKEN or pass --token-file')
    base = o.base.rstrip('/')
    if o.delete:
        print(request('DELETE', f'{base}/api/rooms/{o.id}', token)); return
    extra = {}
    if o.title: extra['X-Room-Title'] = o.title
    if o.view: extra['X-Room-View'] = o.view
    jobs = []
    if o.ply:
        tmp = tempfile.mkdtemp(prefix='roomscape-')
        conv = os.path.join(HERE, '..', 'room-viewer', 'ply2splat.py')
        for version, cap in (('full-phone', 400_000), ('full', 1_500_000)):
            out = os.path.join(tmp, f'{version}.splat')
            subprocess.run([sys.executable, conv, o.ply, out, '--max', str(cap)], check=True)
            jobs.append((version, out))
    elif o.splat:
        jobs.append((o.version, o.splat))
    else:
        sys.exit('Give --ply or --splat (or --delete)')
    for version, path in jobs:
        n = splat_count(path)
        r = upload(base, token, o.id, version, path, {**extra, **({'X-Splat-Count': str(n)} if n else {})})
        print(f'  done: {base}{r.get("link", "")}')


if __name__ == '__main__':
    main()

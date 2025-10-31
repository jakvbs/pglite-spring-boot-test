import os
import sys
import json
import signal
import time

try:
    from py_pglite import PGliteConfig, PGliteManager
except Exception as e:
    print(json.dumps({"event": "ERROR", "reason": "import_error", "message": str(e)}), flush=True)
    sys.exit(3)

def main():
    port = int(os.environ.get("PGLITE_PORT", "54329"))
    host = os.environ.get("PGLITE_HOST", "127.0.0.1")

    try:
        cfg = PGliteConfig(tcp=True, tcp_host=host, tcp_port=port)
        mgr = PGliteManager(config=cfg)
        mgr.__enter__()
    except Exception as e:
        print(json.dumps({"event": "ERROR", "reason": "start_failed", "message": str(e)}), flush=True)
        sys.exit(4)

    print(json.dumps({"event": "READY", "host": host, "port": port, "pid": os.getpid()}), flush=True)

    def shutdown(_sig, _frm):
        try:
            mgr.__exit__(None, None, None)
        finally:
            sys.exit(0)

    signal.signal(signal.SIGTERM, shutdown)
    signal.signal(signal.SIGINT, shutdown)
    while True:
        time.sleep(1)

if __name__ == "__main__":
    main()


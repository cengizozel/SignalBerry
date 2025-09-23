# SignalBerry server
Run:
python -m venv venv
source venv/bin/activate     # Windows: venv\Scripts\activate
pip install -r requirements.txt
export SECRET_TOKEN=changeme  # Windows: set SECRET_TOKEN=changeme
python server.py

Endpoint:
POST /verify  { "secret": "<token>" } -> {"ok": true|false}

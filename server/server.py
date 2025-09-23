import os
from flask import Flask, request, jsonify

app = Flask(__name__)

SECRET = os.environ.get("SECRET_TOKEN", "changeme")

@app.route("/verify", methods=["POST"])
def verify():
    print('verify')
    # accept JSON body, header, or query param (use whichever is easiest from Android)
    data = request.get_json(silent=True) or {}
    token = (
        data.get("secret")
        or request.headers.get("X-Secret")
        or request.args.get("secret")
    )
    ok = (token == SECRET)
    return jsonify(ok=ok), (200 if ok else 401)

if __name__ == "__main__":
    port = int(os.environ.get("PORT", 5000))
    app.run(host="0.0.0.0", port=port, debug=True)

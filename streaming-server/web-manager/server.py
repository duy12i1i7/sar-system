import os
import requests
from flask import Flask, render_template, request, jsonify

app = Flask(__name__)
MEDIAMTX_API = "http://localhost:9997/v3"

# Cho phep dashboard (khac origin, vd http://10.10.10.3:5173) goi API nay.
# Truoc day thieu header nay nen trinh duyet chan -> dropdown chon nguon bi trong.
@app.after_request
def add_cors_headers(resp):
    resp.headers['Access-Control-Allow-Origin'] = '*'
    resp.headers['Access-Control-Allow-Methods'] = 'GET, POST, DELETE, OPTIONS'
    resp.headers['Access-Control-Allow-Headers'] = 'Content-Type'
    return resp

@app.route('/')
def index():
    return render_template('index.html')

@app.route('/api/streams', methods=['GET'])
def get_streams():
    try:
        # Get path configurations
        resp = requests.get(f"{MEDIAMTX_API}/config/paths/list")
        if resp.status_code == 200:
            return jsonify(resp.json())
        return jsonify({"items": []}), 500
    except Exception as e:
        return jsonify({"error": str(e)}), 500

@app.route('/api/streams', methods=['POST'])
def add_stream():
    data = request.json
    name = data.get('name')
    if not name:
        return jsonify({"error": "Name is required"}), 400
    
    try:
        # Add path config (empty config allows publish)
        resp = requests.post(f"{MEDIAMTX_API}/config/paths/add/{name}", json={})
        if resp.status_code == 200:
            return jsonify({"success": True})
        return jsonify({"error": resp.text}), resp.status_code
    except Exception as e:
        return jsonify({"error": str(e)}), 500

@app.route('/api/streams/<name>', methods=['DELETE'])
def delete_stream(name):
    try:
        resp = requests.delete(f"{MEDIAMTX_API}/config/paths/delete/{name}")
        if resp.status_code == 200:
            return jsonify({"success": True})
        return jsonify({"error": resp.text}), resp.status_code
    except Exception as e:
        return jsonify({"error": str(e)}), 500

# Route /api/shutdown da BI BO (07/2026): no tat CA MAY TRAM va nhet mat khau trong code.

if __name__ == '__main__':
    app.run(host='0.0.0.0', port=8080)

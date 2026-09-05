import sys
from fastapi.testclient import TestClient
from app.main import app

client = TestClient(app)


def test_full_sos_flow():
    print("\n==========================================")
    print("  LIFE GUARD END-TO-END API TEST SUITE   ")
    print("==========================================\n")

    # 1. Test Root Endpoint
    res = client.get("/")
    print(f"1. GET / -> Status: {res.status_code}, Response: {res.json()}")
    assert res.status_code == 200

    # 2. Register Victim User (Alice)
    res_alice = client.post("/api/v1/users/register", json={
        "full_name": "Alice Smith (Victim)",
        "phone_number": "+1111111111",
        "pin": "1234",
        "emergency_contact_phone": "+1999999999",
        "fcm_token": "fcm_token_alice"
    })
    print(f"\n2. POST /api/v1/users/register (Alice) -> Status: {res_alice.status_code}")
    print(f"   Response: {res_alice.json()}")
    assert res_alice.status_code == 201
    alice_id = res_alice.json()["user"]["id"]

    # 3. Register Responder User (Bob)
    res_bob = client.post("/api/v1/users/register", json={
        "full_name": "Bob Johnson (Responder)",
        "phone_number": "+2222222222",
        "pin": "5678",
        "emergency_contact_phone": "+1888888888",
        "fcm_token": "fcm_token_bob"
    })
    print(f"\n3. POST /api/v1/users/register (Bob) -> Status: {res_bob.status_code}")
    print(f"   Response: {res_bob.json()}")
    assert res_bob.status_code == 201
    bob_id = res_bob.json()["user"]["id"]

    # 4. Update Alice Location (Bengaluru City Center)
    res_loc_alice = client.post("/api/v1/location/update", json={
        "user_id": alice_id,
        "latitude": 12.971598,
        "longitude": 77.594566,
        "speed": 1.2,
        "heading": 45.0
    })
    print(f"\n4. POST /api/v1/location/update (Alice) -> Status: {res_loc_alice.status_code}")
    print(f"   Response: {res_loc_alice.json()}")
    assert res_loc_alice.status_code == 200

    # 5. Update Bob Location (~250m away from Alice)
    res_loc_bob = client.post("/api/v1/location/update", json={
        "user_id": bob_id,
        "latitude": 12.971800,
        "longitude": 77.594700,
        "speed": 0.0,
        "heading": 0.0
    })
    print(f"\n5. POST /api/v1/location/update (Bob) -> Status: {res_loc_bob.status_code}")
    print(f"   Response: {res_loc_bob.json()}")
    assert res_loc_bob.status_code == 200

    # 6. Test Nearby User Search
    res_nearby = client.post("/api/v1/location/nearby", json={
        "user_id": alice_id,
        "latitude": 12.971598,
        "longitude": 77.594566,
        "radius_meters": 500.0
    })
    print(f"\n6. POST /api/v1/location/nearby -> Status: {res_nearby.status_code}")
    print(f"   Response: {res_nearby.json()}")
    assert res_nearby.status_code == 200

    # 7. Trigger Emergency SOS Alert (Route Deviation)
    res_sos = client.post("/api/v1/alerts/trigger", json={
        "user_id": alice_id,
        "alert_type": "ROUTE_DEVIATION",
        "latitude": 12.971598,
        "longitude": 77.594566,
        "radius_meters": 500.0
    })
    print(f"\n7. POST /api/v1/alerts/trigger (SOS) -> Status: {res_sos.status_code}")
    print(f"   Response: {res_sos.json()}")
    assert res_sos.status_code == 201
    alert_id = res_sos.json()["alert_id"]

    # 8. Resolve Alert with PIN Verification ("1234")
    res_resolve = client.post("/api/v1/alerts/resolve", json={
        "alert_id": alert_id,
        "user_id": alice_id,
        "pin": "1234"
    })
    print(f"\n8. POST /api/v1/alerts/resolve -> Status: {res_resolve.status_code}")
    print(f"   Response: {res_resolve.json()}")
    assert res_resolve.status_code == 200

    print("\n==========================================")
    print("  ALL LIFE GUARD SOS API TESTS PASSED SUCCESSFULLY! ")
    print("==========================================\n")


if __name__ == "__main__":
    test_full_sos_flow()

"""Smoke test end-to-end cho stack docker compose (app :8080, fake-bank, mailpit :8025).
Can du lieu demo sach: chay `docker compose down -v` roi `docker compose up -d --build` truoc khi chay script nay."""
import json, re, sys, time, uuid, datetime, urllib.request, urllib.error

BASE = "http://localhost:8080/api/v1"
MAILPIT = "http://localhost:8025/api/v1"
PWD = "ChangeMe@12345"
results = []


def call(method, path, body=None, token=None, base=BASE):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(base + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            raw = r.read().decode()
            return r.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as e:
        raw = e.read().decode()
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, raw


def check(uc, name, ok, detail=""):
    results.append((uc, name, bool(ok), detail))
    print(("PASS " if ok else "FAIL ") + f"[{uc}] {name}" + (f"  -> {detail}" if detail and not ok else ""))


def code_of(body):
    return body.get("code") if isinstance(body, dict) else None


def login(email):
    s, b = call("POST", "/auth/login", {"email": email, "password": PWD})
    assert s == 200, (email, s, b)
    return b["accessToken"]


def wait_status(token, bid, wanted, secs=40):
    st = None
    for _ in range(secs):
        s, b = call("GET", f"/bookings/{bid}", token=token)
        st = b.get("status") if isinstance(b, dict) else None
        if st in wanted:
            return st
        time.sleep(1)
    return st


today = datetime.date.today()
d = lambda n: (today + datetime.timedelta(days=n)).isoformat()

# ---------------- UC-02 / phân quyền
s, b = call("GET", "/actuator/health")
check("OPS", "health UP", s == 200 and b.get("status") == "UP", b)
cust, host, admin, sup = (login(f"{r}@datphong.local") for r in ("customer", "host", "admin", "support"))
check("UC-02", "đăng nhập 4 vai trò seed", True)
s, b = call("POST", "/auth/login", {"email": "customer@datphong.local", "password": "sai-mat-khau"})
check("UC-02", "sai mật khẩu -> 401", s == 401, (s, b))
s, b = call("POST", "/bookings", {"roomId": 1})
check("UC-02", "không token -> 401", s == 401, (s, b))
s, b = call("GET", "/admin/roles", token=cust)
check("UC-02", "CUSTOMER gọi API admin -> 403", s == 403, (s, b))

# ---------------- UC-01 đăng ký + xác minh qua Mailpit
email = f"new{uuid.uuid4().hex[:6]}@t.local"
s, b = call("POST", "/auth/register", {"email": email, "password": PWD, "fullName": "Khach Moi", "phone": "0900000000", "acceptTerms": True})
check("UC-01", "đăng ký -> PENDING_VERIFICATION", s in (200, 201) and b.get("status") == "PENDING_VERIFICATION", (s, b))
s, b = call("POST", "/auth/register", {"email": email, "password": PWD, "fullName": "Khach Moi", "phone": "0900000000", "acceptTerms": True})
check("UC-01", "email trùng -> EMAIL_ALREADY_EXISTS", code_of(b) == "EMAIL_ALREADY_EXISTS", (s, b))
code = None
for _ in range(30):
    s, m = call("GET", f"/search?query=to:{email}", base=MAILPIT)
    msgs = m.get("messages", []) if isinstance(m, dict) else []
    for msg in msgs:
        s2, full = call("GET", f"/message/{msg['ID']}", base=MAILPIT)
        hit = re.search(r"\b(\d{6})\b", (full.get("Text") or "") + (full.get("HTML") or ""))
        if hit:
            code = hit.group(1)
    if code:
        break
    time.sleep(1)
check("UC-32", "email xác minh tới Mailpit", code is not None, "không thấy mã trong Mailpit")
if code:
    s, b = call("POST", "/auth/verify", {"email": email, "code": code})
    check("UC-01", "xác minh mã -> ACTIVE (login được)", s in (200, 204) and call("POST", "/auth/login", {"email": email, "password": PWD})[0] == 200, (s, b))

# ---------------- UC-04/05: dữ liệu HOST
s, props = call("GET", "/host/properties", token=host)
pid = props[0]["id"]
s, rooms = call("GET", f"/host/properties/{pid}/rooms", token=host)
room = {r["roomCode"]: r["id"] for r in rooms}
check("UC-05", "HOST xem phòng của mình", "D101" in room, rooms)
s, b = call("GET", f"/host/properties/{pid}/rooms", token=admin)
check("UC-04", "người không sở hữu xem property -> bị chặn", s in (403, 404), (s, b))

# ---------------- UC-07/08
s, b = call("GET", f"/rooms/search?checkIn={d(1)}&checkOut={d(3)}&guests=2")
check("UC-07", "tìm kiếm công khai", s == 200, (s, b))
s, b = call("GET", f"/properties?checkIn={d(1)}&checkOut={d(3)}&guests=2&sort=drop_table")
check("UC-07", "sort ngoài whitelist -> VALIDATION_ERROR", s == 400 and code_of(b) == "VALIDATION_ERROR", (s, b))
s, q = call("GET", f"/rooms/{room['D101']}/quote?checkIn={d(0)}&checkOut={d(2)}&guests=2")
check("UC-08", "báo giá theo từng đêm", s == 200 and len(q.get("nightly", [])) == 2 and q.get("availableNow"), (s, q))

# ---------------- UC-09 tạo booking + idempotency + tồn kho
keyA = "smoke-A-" + uuid.uuid4().hex[:8]
bodyA = {"roomId": room["D101"], "checkIn": d(0), "checkOut": d(2), "guestCount": 2, "idempotencyKey": keyA}
s, A = call("POST", "/bookings", bodyA, cust)
check("UC-09", "tạo booking -> 201 PENDING_PAYMENT", s == 201 and A.get("status") == "PENDING_PAYMENT", (s, A))
check("UC-09", "tổng tiền server tính = báo giá", A.get("totalAmount") == q.get("total"), (A.get("totalAmount"), q.get("total")))
s, b = call("POST", "/bookings", bodyA, cust)
check("UC-09", "gửi lại cùng key -> 200 cùng booking", s == 200 and b.get("id") == A["id"], (s, b))
s, b = call("POST", "/bookings", dict(bodyA, specialNote="khác"), cust)
check("UC-09", "cùng key khác payload -> IDEMPOTENCY_CONFLICT", code_of(b) == "IDEMPOTENCY_CONFLICT", (s, b))
s, b = call("POST", "/bookings", {"roomId": room["D101"], "checkIn": d(1), "checkOut": d(3), "guestCount": 2, "idempotencyKey": "smoke-ov-" + uuid.uuid4().hex[:8]}, cust)
check("UC-09", "đặt chồng ngày -> ROOM_UNAVAILABLE", code_of(b) == "ROOM_UNAVAILABLE", (s, b))
s, b = call("POST", "/bookings", {"roomId": room["D102"], "checkIn": d(30), "checkOut": d(31), "guestCount": 2, "idempotencyKey": "smoke-pc-" + uuid.uuid4().hex[:8], "expectedTotal": 1}, cust)
check("UC-08", "tổng tiền client sai -> PRICE_CHANGED", code_of(b) == "PRICE_CHANGED", (s, b))

# ---------------- UC-10 thanh toán TEST-OK -> CONFIRMED
s, p = call("POST", "/payments", {"bookingId": A["id"], "idempotencyKey": "pay-" + keyA, "payerAccount": "TEST-OK"}, cust)
check("UC-10", "khởi tạo thanh toán", s in (200, 201), (s, p))
st = wait_status(cust, A["id"], {"CONFIRMED"})
check("UC-10", "TEST-OK -> booking CONFIRMED", st == "CONFIRMED", st)
s, p2 = call("POST", "/payments", {"bookingId": A["id"], "idempotencyKey": "pay-" + keyA, "payerAccount": "TEST-OK"}, cust)
check("UC-10", "gửi lại thanh toán cùng key -> cùng payment", p2.get("id") == p.get("id"), (p, p2))

# ---------------- UC-14/15/16 HOST xử lý, lưu trú, đánh giá
s, hd = call("GET", f"/host/bookings/{A['id']}", token=host)
ver = hd["booking"]["version"]
s, b = call("PATCH", f"/host/bookings/{A['id']}", {"expectedArrivalTime": "15:30", "hostNote": "Khách VIP", "version": ver}, host)
s, hd = call("GET", f"/host/bookings/{A['id']}", token=host)
check("UC-14", "HOST cập nhật giờ đến + ghi chú", hd.get("expectedArrivalTime", "").startswith("15:30") and hd.get("hostNote") == "Khách VIP", (s, b, hd.get("expectedArrivalTime")))
s, b = call("GET", f"/bookings/{A['id']}", token=cust)
check("UC-14", "ghi chú HOST không lộ cho khách", "Khách VIP" not in json.dumps(b, ensure_ascii=False), "")
s, b = call("PATCH", f"/host/bookings/{A['id']}", {"hostNote": "x", "version": ver}, host)
check("UC-14", "version cũ -> VERSION_CONFLICT", code_of(b) == "VERSION_CONFLICT", (s, b))
s, b = call("POST", f"/host/bookings/{A['id']}/check-in", token=host)
s2, b2 = call("POST", f"/host/bookings/{A['id']}/check-in", token=host)
check("UC-15", "check-in (gọi lặp không lỗi)", s == 200 and s2 == 200, (s, b, s2, b2))
s, b = call("POST", f"/host/bookings/{A['id']}/check-out", token=host)
st = call("GET", f"/bookings/{A['id']}", token=cust)[1].get("status")
check("UC-15", "check-out -> COMPLETED", st == "COMPLETED", (s, b, st))
s, b = call("POST", f"/bookings/{A['id']}/reviews", {"rating": 5, "comment": "Phòng sạch, nhân viên thân thiện"}, cust)
check("UC-16", "đánh giá booking COMPLETED", s in (200, 201), (s, b))
s, b = call("POST", f"/bookings/{A['id']}/reviews", {"rating": 4, "comment": "lần 2"}, cust)
check("UC-16", "đánh giá lần 2 -> REVIEW_ALREADY_EXISTS", code_of(b) == "REVIEW_ALREADY_EXISTS", (s, b))

# ---------------- UC-10 nhánh thất bại: TEST-POOR
s, B = call("POST", "/bookings", {"roomId": room["D102"], "checkIn": d(5), "checkOut": d(7), "guestCount": 2, "idempotencyKey": "smoke-B-" + uuid.uuid4().hex[:8]}, cust)
s, pb = call("POST", "/payments", {"bookingId": B["id"], "idempotencyKey": "pay-B-" + uuid.uuid4().hex[:8], "payerAccount": "TEST-POOR"}, cust)
pst = pb.get("status")
for _ in range(20):
    if pst == "FAILED":
        break
    time.sleep(1)
    pst = call("GET", f"/payments/{pb['id']}", token=cust)[1].get("status")
st = call("GET", f"/bookings/{B['id']}", token=cust)[1].get("status")
check("UC-10", "TEST-POOR -> payment FAILED, booking vẫn PENDING_PAYMENT", pst == "FAILED" and st == "PENDING_PAYMENT", (pst, st))

# ---------------- UC-35 TEST-DUP: callback lặp chỉ một hiệu ứng
s, D = call("POST", "/bookings", {"roomId": room["F201"], "checkIn": d(20), "checkOut": d(21), "guestCount": 3, "idempotencyKey": "smoke-D-" + uuid.uuid4().hex[:8]}, cust)
s, pd = call("POST", "/payments", {"bookingId": D["id"], "idempotencyKey": "pay-D-" + uuid.uuid4().hex[:8], "payerAccount": "TEST-DUP"}, cust)
st = wait_status(cust, D["id"], {"CONFIRMED"})
check("UC-35", "TEST-DUP -> CONFIRMED đúng một lần", st == "CONFIRMED", st)

# ---------------- UC-12/13 hủy booking đã thanh toán -> refund
s, C = call("POST", "/bookings", {"roomId": room["D103"], "checkIn": d(10), "checkOut": d(12), "guestCount": 2, "idempotencyKey": "smoke-C-" + uuid.uuid4().hex[:8]}, cust)
call("POST", "/payments", {"bookingId": C["id"], "idempotencyKey": "pay-C-" + uuid.uuid4().hex[:8], "payerAccount": "TEST-OK"}, cust)
st = wait_status(cust, C["id"], {"CONFIRMED"})
s, pv = call("GET", f"/bookings/{C['id']}/cancel-preview", token=cust)
check("UC-12", "xem trước phí hủy (miễn phí, hoàn 100%)", s == 200 and pv.get("freeCancellation") and pv.get("refundableAmount") == C["totalAmount"], (st, s, pv))
ck = "smoke-cancel-" + uuid.uuid4().hex[:8]
s, cr = call("POST", f"/bookings/{C['id']}/cancel", {"idempotencyKey": ck, "reason": "Đổi kế hoạch"}, cust)
check("UC-12", "hủy -> REFUND_PENDING + refund", s == 200 and cr.get("status") == "REFUND_PENDING" and cr.get("refundId"), (s, cr))
s, cr2 = call("POST", f"/bookings/{C['id']}/cancel", {"idempotencyKey": ck, "reason": "Đổi kế hoạch"}, cust)
check("UC-12", "hủy lặp -> idempotent, không tạo refund thứ 2", cr2.get("refundId") == cr.get("refundId"), (cr, cr2))
st = wait_status(cust, C["id"], {"CANCELLED"}, secs=60)
check("UC-13", "refund thành công -> booking CANCELLED", st == "CANCELLED", st)
s, rq = call("GET", f"/rooms/{room['D103']}/quote?checkIn={d(10)}&checkOut={d(12)}&guests=2")
check("UC-12", "phòng được giải phóng sau hủy", rq.get("availableNow") is True, rq)

# ---------------- UC-17 thông báo
s, n = call("GET", "/notifications", token=cust)
titles = json.dumps(n, ensure_ascii=False)
check("UC-17", "thông báo 'Đang giữ chỗ' (BOOKING_CREATED)", "Đang giữ chỗ" in titles, titles[:300])
check("UC-17", "thông báo 'Đã ghi nhận yêu cầu hoàn tiền'", "yêu cầu hoàn tiền" in titles, "")

# ---------------- UC-19 / UC-20 / UC-22 / UC-26
s, t = call("GET", f"/support/bookings/{C['id']}/trace", token=sup)
check("UC-19", "SUPPORT truy vết booking->payment->refund", s == 200 and t.get("payments") and t.get("refunds") and t.get("statusHistory"), (s, str(t)[:300]))
check("UC-19", "trace không lộ payerAccount/idempotencyKey", "payerAccount" not in json.dumps(t) and "idempotencyKey" not in json.dumps(t), "")
s, b = call("GET", f"/support/bookings/{C['id']}/trace", token=cust)
check("UC-19", "CUSTOMER gọi trace -> 403", s == 403, (s, b))
s, r = call("GET", "/admin/roles", token=admin)
check("UC-20", "ADMIN xem role + permission", s == 200 and any(x["code"] == "ADMIN" for x in r), (s, r))
s, a = call("GET", "/admin/amenities", token=admin)
check("UC-20", "ADMIN xem danh mục tiện ích", s == 200 and len(a) > 0, (s, a))
qb = {"metrics": ["bookings", "roomNights", "gross"], "groupBy": ["status"], "timeAxis": "createdAt", "from": d(-7), "to": d(1), "comparePrevious": True}
s, r = call("POST", "/analytics/query", qb, host)
check("UC-22", "thống kê đa chiều + so sánh kỳ trước", s == 200 and "previous" in r and r["totals"]["bookings"] >= 4, (s, str(r)[:300]))
s, r = call("POST", "/analytics/query", dict(qb, sources=["web; drop"]), host)
check("UC-22", "bộ lọc nguồn sai -> VALIDATION_ERROR", code_of(r) == "VALIDATION_ERROR", (s, r))
s, r = call("GET", f"/analytics/overview?from={d(-7)}&to={d(1)}&propertyId={pid}", token=host)
check("UC-21", "dashboard HOST có asOf/sourceType", s == 200 and r.get("asOf") and r.get("sourceType"), (s, str(r)[:200]))
s, r = call("GET", f"/analytics/overview?from={d(-7)}&to={d(1)}&propertyId=999999", token=host)
check("UC-18", "HOST xem property ngoài phạm vi -> FORBIDDEN_SCOPE", code_of(r) == "FORBIDDEN_SCOPE", (s, r))
s, r = call("POST", "/analytics/cache/invalidate?reason=smoke", token=admin)
check("UC-26", "ADMIN làm mới cache analytics", s == 200 and r.get("cacheVersion", 0) >= 1, (s, r))
s, r = call("POST", "/analytics/cache/invalidate?reason=smoke", token=host)
check("UC-26", "HOST làm mới cache -> 403", s == 403, (s, r))

# ---------------- UC-32 email giao dịch
s, m = call("GET", "/messages?limit=200", base=MAILPIT)
check("UC-32", "Mailpit nhận email giao dịch", m.get("total", 0) >= 3, m.get("total"))

ok = sum(1 for r in results if r[2])
print(f"\n==> {ok}/{len(results)} PASS")
sys.exit(0 if ok == len(results) else 1)

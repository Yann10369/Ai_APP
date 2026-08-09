"""接口合规性测试：逐个验证 26 个接口的响应与 interface.md 的差异。"""
import io
import json
import sys
import time
import uuid
from typing import Any

# Force UTF-8 stdout for Windows console
sys.stdout.reconfigure(encoding="utf-8")

import httpx
from PIL import Image

BASE = "http://127.0.0.1:8000"

TAG = uuid.uuid4().hex[:8]
USERNAME = f"tester_{TAG}"
PASSWORD = "secret123"
EMAIL = f"{USERNAME}@example.com"


def make_jpeg(w: int = 320, h: int = 240, color=(120, 80, 200)) -> bytes:
    buf = io.BytesIO()
    Image.new("RGB", (w, h), color).save(buf, "JPEG")
    return buf.getvalue()


class Result:
    def __init__(self, name: str, method: str, path: str):
        self.name = name
        self.method = method
        self.path = path
        self.passed = True
        self.notes: list[str] = []
        self.response: Any = None
        self.expected_keys: list[str] = []

    def check(self, condition: bool, msg: str):
        if not condition:
            self.passed = False
            self.notes.append(f"❌ {msg}")
        else:
            self.notes.append(f"✅ {msg}")

    def warn(self, msg: str):
        self.notes.append(f"⚠️  {msg}")


def print_result(r: Result):
    status = "PASS" if r.passed else "FAIL"
    print(f"\n[{status}] {r.method} {r.path}  —  {r.name}")
    for n in r.notes:
        print(f"    {n}")


def section(title: str):
    print(f"\n{'='*70}\n{title}\n{'='*70}")


def main():
    with httpx.Client(base_url=BASE, timeout=30) as c:
        section("预检：/health")
        r = c.get("/health")
        print(f"  /health → {r.status_code} {r.json()}")
        assert r.status_code == 200

        section("模块一：认证 (3 接口)")

        r1 = Result("用户注册", "POST", "/api/v1/auth/register")
        r = c.post("/api/v1/auth/register", json={
            "username": USERNAME, "password": PASSWORD, "email": EMAIL,
        })
        r1.response = r.json()
        r1.check(r.status_code == 200, f"HTTP 200 (实际 {r.status_code})")
        body = r1.response
        r1.check(body.get("code") == 200, f"code=200 (实际 {body.get('code')})")
        r1.check("userId" in (body.get("data") or {}), "data.userId 存在")
        r1.check((body.get("data") or {}).get("username") == USERNAME, f"data.username={USERNAME}")
        r1.check("token" not in (body.get("data") or {}), "注册不返回 token（符合规范）")
        print_result(r1)

        r2 = Result("用户登录", "POST", "/api/v1/auth/login")
        r = c.post("/api/v1/auth/login", json={"username": USERNAME, "password": PASSWORD})
        r2.response = r.json()
        r2.check(r.status_code == 200, f"HTTP 200 (实际 {r.status_code})")
        body = r2.response
        r2.check(body.get("code") == 200, f"code=200 (实际 {body.get('code')})")
        d = body.get("data") or {}
        r2.check("userId" in d, "data.userId 存在")
        r2.check("token" in d and isinstance(d["token"], str) and len(d["token"]) > 20, "data.token 存在且为字符串")
        r2.check("expiresIn" in d, "data.expiresIn 存在")
        r2.check(d.get("username") == USERNAME, f"data.username={USERNAME}")
        token = d.get("token")
        auth = {"Authorization": f"Bearer {token}"} if token else {}
        print_result(r2)

        r3 = Result("用户登出", "POST", "/api/v1/auth/logout")
        r = c.post("/api/v1/auth/logout", headers=auth)
        r3.response = r.json()
        r3.check(r.status_code == 200, f"HTTP 200 (实际 {r.status_code})")
        r3.check(r3.response.get("code") == 200, f"code=200 (实际 {r3.response.get('code')})")
        r3.check("message" in r3.response, "message 字段存在")
        r3.check(r3.response.get("data") is None, "data=null（规范要求）")
        print_result(r3)

        section("模块二：用户信息 (3 接口)")

        r4 = Result("获取当前用户信息", "GET", "/api/v1/users/me")
        r = c.get("/api/v1/users/me", headers=auth)
        r4.response = r.json()
        r4.check(r.status_code == 200, f"HTTP 200 (实际 {r.status_code})")
        d = r4.response.get("data") or {}
        for k in ["userId", "username", "email", "avatarUrl", "createdAt"]:
            r4.check(k in d, f"data.{k} 存在")
        r4.check(d.get("username") == USERNAME, f"username={USERNAME}")
        r4.check(d.get("email") == EMAIL, f"email={EMAIL}")
        print_result(r4)

        r5 = Result("获取用户统计数据", "GET", "/api/v1/users/me/statistics")
        r = c.get("/api/v1/users/me/statistics", headers=auth)
        r5.response = r.json()
        r5.check(r.status_code == 200, f"HTTP 200 (实际 {r.status_code})")
        d = r5.response.get("data") or {}
        for k in ["totalPhotos", "analyzedPhotos", "favoriteCount", "categoryDistribution"]:
            r5.check(k in d, f"data.{k} 存在")
        cd = d.get("categoryDistribution") or {}
        for k in ["scene", "emotion", "tag"]:
            r5.check(k in cd, f"categoryDistribution.{k} 存在")
        print_result(r5)

        r6 = Result("获取收藏列表", "GET", "/api/v1/users/me/favorites")
        r = c.get("/api/v1/users/me/favorites?page=1&pageSize=20", headers=auth)
        r6.response = r.json()
        r6.check(r.status_code == 200, f"HTTP 200 (实际 {r.status_code})")
        d = r6.response.get("data") or {}
        r6.check(isinstance(d.get("list"), list), "data.list 是数组")
        for k in ["total", "page", "pageSize"]:
            r6.check(k in d, f"data.{k} 存在")
        print_result(r6)

        section("模块三：照片管理 (10 接口)")

        r7 = Result("批量上传照片", "POST", "/api/v1/photos/upload")
        files = [("files", ("a.jpg", make_jpeg(), "image/jpeg")),
                 ("files", ("b.jpg", make_jpeg(640, 480, (200, 100, 50)), "image/jpeg"))]
        r = c.post("/api/v1/photos/upload", files=files, headers=auth)
        r7.response = r.json()
        r7.check(r.status_code == 200, f"HTTP 200 (实际 {r.status_code})")
        d = r7.response.get("data") or {}
        for k in ["successCount", "failCount", "uploadedPhotos", "failedFiles"]:
            r7.check(k in d, f"data.{k} 存在")
        r7.check(d.get("successCount") == 2, f"successCount=2 (实际 {d.get('successCount')})")
        first_photo = d["uploadedPhotos"][0] if d.get("uploadedPhotos") else None
        photo_id = first_photo.get("photoId") if first_photo else None
        for k in ["photoId", "originalName", "thumbnailUrl", "size", "analysisStatus"]:
            if first_photo:
                r7.check(k in first_photo, f"uploadedPhotos[0].{k} 存在")
        r7.check(first_photo.get("analysisStatus") == "pending" if first_photo else False,
                 f"analysisStatus=pending (实际 {first_photo.get('analysisStatus') if first_photo else 'N/A'})")
        print_result(r7)
        print(f"  → 上传得到 photo_id={photo_id}")

        if photo_id:
            for i in range(15):
                r = c.get("/api/v1/ai/status", headers=auth)
                s = r.json().get("data", {})
                if s.get("done", 0) >= 2:
                    break
                time.sleep(0.5)

        r8 = Result("获取照片列表", "GET", "/api/v1/photos")
        r = c.get("/api/v1/photos?page=1&pageSize=20", headers=auth)
        r8.response = r.json()
        r8.check(r.status_code == 200, f"HTTP 200 (实际 {r.status_code})")
        d = r8.response.get("data") or {}
        r8.check(isinstance(d.get("list"), list), "data.list 是数组")
        for k in ["total", "page", "pageSize"]:
            r8.check(k in d, f"data.{k} 存在")
        if d.get("list"):
            item = d["list"][0]
            for k in ["photoId", "thumbnailUrl", "width", "height", "createdAt", "isFavorite", "analysisStatus"]:
                r8.check(k in item, f"list[0].{k} 存在")
            r8.check(item.get("analysisStatus") == "done", f"analysisStatus=done (实际 {item.get('analysisStatus')})")
        print_result(r8)

        r9 = Result("获取最近照片", "GET", "/api/v1/photos/recent")
        r = c.get("/api/v1/photos/recent?limit=5", headers=auth)
        r9.response = r.json()
        r9.check(r.status_code == 200, f"HTTP 200 (实际 {r.status_code})")
        d = r9.response.get("data") or {}
        r9.check(isinstance(d.get("list"), list), "data.list 是数组")
        if d.get("list"):
            item = d["list"][0]
            for k in ["photoId", "thumbnailUrl", "createdAt"]:
                r9.check(k in item, f"list[0].{k} 存在")
        print_result(r9)

        r10 = Result("获取照片详情", "GET", f"/api/v1/photos/{{photoId}}")
        if photo_id:
            r = c.get(f"/api/v1/photos/{photo_id}", headers=auth)
            r10.response = r.json()
            r10.check(r.status_code == 200, f"HTTP 200 (实际 {r.status_code})")
            d = r10.response.get("data") or {}
            for k in ["photoId", "originalUrl", "thumbnailUrl", "metadata", "aiAnalysis", "isFavorite", "createdAt"]:
                r10.check(k in d, f"data.{k} 存在")
            meta = d.get("metadata") or {}
            for k in ["fileName", "size", "width", "height", "shotAt"]:
                r10.check(k in meta, f"metadata.{k} 存在")
            ai = d.get("aiAnalysis")
            if ai:
                r10.check("description" in ai, "aiAnalysis.description 存在")
                r10.check("scene" in ai, "aiAnalysis.scene 存在")
                r10.check("emotion" in ai, "aiAnalysis.emotion 存在")
                r10.check("tags" in ai, "aiAnalysis.tags 存在")
                r10.check(isinstance(ai.get("tags"), list), "aiAnalysis.tags 是数组")
        else:
            r10.check(False, "无 photo_id 可用")
        print_result(r10)

        r11 = Result("修改照片信息", "PATCH", f"/api/v1/photos/{{photoId}}")
        if photo_id:
            r = c.patch(f"/api/v1/photos/{photo_id}",
                        json={"tags": ["🏖️ 海滩", "🏙️ 城市"], "description": "测试描述"},
                        headers=auth)
            r11.response = r.json()
            r11.check(r.status_code == 200, f"HTTP 200 (实际 {r.status_code})")
            r11.check(r11.response.get("code") == 200, f"code=200 (实际 {r11.response.get('code')})")
            r11.check("message" in r11.response, "message 字段存在")
        else:
            r11.check(False, "无 photo_id 可用")
        print_result(r11)

        r14 = Result("收藏照片", "POST", f"/api/v1/photos/{{photoId}}/favorite")
        if photo_id:
            r = c.post(f"/api/v1/photos/{photo_id}/favorite", headers=auth)
            r14.response = r.json()
            r14.check(r.status_code == 200, f"HTTP 200 (实际 {r.status_code})")
            r14.check(r14.response.get("code") == 200, f"code=200")
            r14.check("message" in r14.response, "message 字段存在")
        else:
            r14.check(False, "无 photo_id 可用")
        print_result(r14)

        r15 = Result("取消收藏", "DELETE", f"/api/v1/photos/{{photoId}}/favorite")
        if photo_id:
            r = c.delete(f"/api/v1/photos/{photo_id}/favorite", headers=auth)
            r15.response = r.json()
            r15.check(r.status_code == 200, f"HTTP 200 (实际 {r.status_code})")
            r15.check(r15.response.get("code") == 200, f"code=200")
        else:
            r15.check(False, "无 photo_id 可用")
        print_result(r15)

        r16 = Result("搜索照片", "POST", "/api/v1/photos/search")
        r = c.post("/api/v1/photos/search",
                   json={"query": "海滩", "page": 1, "pageSize": 20}, headers=auth)
        r16.response = r.json()
        r16.check(r.status_code == 200, f"HTTP 200 (实际 {r.status_code})")
        d = r16.response.get("data") or {}
        r16.check(isinstance(d.get("list"), list), "data.list 是数组")
        for k in ["total", "page", "pageSize"]:
            r16.check(k in d, f"data.{k} 存在")
        if d.get("list"):
            item = d["list"][0]
            for k in ["photoId", "thumbnailUrl", "matchedTags", "score"]:
                r16.check(k in item, f"list[0].{k} 存在")
        print_result(r16)

        r12 = Result("删除单张照片", "DELETE", f"/api/v1/photos/{{photoId}}")
        if photo_id:
            r = c.delete(f"/api/v1/photos/{photo_id}", headers=auth)
            r12.response = r.json()
            r12.check(r.status_code == 200, f"HTTP 200 (实际 {r.status_code})")
            r12.check(r12.response.get("code") == 200, f"code=200")
            r12.check("message" in r12.response, "message 字段存在")
            verify_resp = c.get(f"/api/v1/photos/{photo_id}", headers=auth)
            r12.check(verify_resp.json().get("code") == 404, f"删除后 GET 返回 404 (实际 {verify_resp.json().get('code')})")
        else:
            r12.check(False, "无 photo_id 可用")
        print_result(r12)

        r13 = Result("批量删除照片", "DELETE", "/api/v1/photos/batch")
        files = [("files", (f"bd{i}.jpg", make_jpeg(200, 200, (10*(i+1), 50, 100)), "image/jpeg")) for i in range(2)]
        rr2 = c.post("/api/v1/photos/upload", files=files, headers=auth)
        uploaded = (rr2.json().get("data") or {}).get("uploadedPhotos", [])
        bd_pids = [p["photoId"] for p in uploaded]
        if not bd_pids:
            r13.check(False, "上传失败，无 photo_ids")
        r = c.request("DELETE", "/api/v1/photos/batch", json={"photoIds": bd_pids}, headers=auth)
        r13.response = r.json()
        r13.check(r.status_code == 200, f"HTTP 200 (实际 {r.status_code})")
        d = r13.response.get("data") or {}
        for k in ["successCount", "failCount"]:
            r13.check(k in d, f"data.{k} 存在")
        r13.check(d.get("successCount") == len(bd_pids), f"successCount={len(bd_pids)}")
        print_result(r13)

        section("模块四：分类 (3 接口)")

        r17 = Result("首页分类预览", "GET", "/api/v1/categories/preview")
        r = c.get("/api/v1/categories/preview?previewSize=4", headers=auth)
        r17.response = r.json()
        r17.check(r.status_code == 200, f"HTTP 200 (实际 {r.status_code})")
        d = r17.response.get("data") or {}
        for k in ["scene", "emotion", "tag"]:
            r17.check(k in d, f"data.{k} 存在")
        if d.get("scene"):
            item = d["scene"][0]
            for k in ["categoryId", "categoryName", "photoCount", "previewPhotos"]:
                r17.check(k in item, f"scene[0].{k} 存在")
        print_result(r17)

        r18 = Result("分类列表", "GET", "/api/v1/categories")
        r = c.get("/api/v1/categories?type=scene", headers=auth)
        r18.response = r.json()
        r18.check(r.status_code == 200, f"HTTP 200 (实际 {r.status_code})")
        d = r18.response.get("data") or {}
        r18.check(d.get("type") == "scene", f"data.type=scene (实际 {d.get('type')})")
        r18.check(isinstance(d.get("list"), list), "data.list 是数组")
        r18.check(len(d.get("list", [])) == 20, f"scene 分类数=20 (实际 {len(d.get('list', []))})")
        if d.get("list"):
            item = d["list"][0]
            for k in ["categoryId", "categoryName", "photoCount", "coverThumbnail"]:
                r18.check(k in item, f"list[0].{k} 存在")
        print_result(r18)

        r19 = Result("分类下照片", "GET", "/api/v1/categories/{categoryId}/photos")
        r = c.get("/api/v1/categories/1/photos?page=1&pageSize=20", headers=auth)
        r19.response = r.json()
        r19.check(r.status_code == 200, f"HTTP 200 (实际 {r.status_code})")
        d = r19.response.get("data") or {}
        for k in ["categoryId", "categoryName", "list", "total", "page", "pageSize"]:
            r19.check(k in d, f"data.{k} 存在")
        r19.check(d.get("categoryId") == 1, "data.categoryId=1")
        if d.get("list"):
            item = d["list"][0]
            for k in ["photoId", "thumbnailUrl", "createdAt"]:
                r19.check(k in item, f"list[0].{k} 存在")
        print_result(r19)

        section("模块五：AI (2 接口)")

        r20 = Result("AI 分析进度", "GET", "/api/v1/ai/status")
        r = c.get("/api/v1/ai/status", headers=auth)
        r20.response = r.json()
        r20.check(r.status_code == 200, f"HTTP 200 (实际 {r.status_code})")
        d = r20.response.get("data") or {}
        for k in ["total", "done", "pending", "progress"]:
            r20.check(k in d, f"data.{k} 存在")
        if d.get("total", 0) > 0:
            r20.check(isinstance(d.get("progress"), float), "progress 是浮点数")
        print_result(r20)

        r21 = Result("手动重新分析", "POST", "/api/v1/ai/reanalyze")
        files = [("files", ("r.jpg", make_jpeg(150, 150, (200, 50, 75)), "image/jpeg"))]
        rr = c.post("/api/v1/photos/upload", files=files, headers=auth)
        uploaded = (rr.json().get("data") or {}).get("uploadedPhotos", [])
        if uploaded:
            reanalyze_pid = uploaded[0]["photoId"]
        else:
            reanalyze_pid = None
            r21.check(False, "上传失败，无 photo_id")
        if reanalyze_pid is not None:
            r = c.post("/api/v1/ai/reanalyze", json={"photoIds": [reanalyze_pid]}, headers=auth)
        r21.response = r.json()
        r21.check(r.status_code == 200, f"HTTP 200 (实际 {r.status_code})")
        d = r21.response.get("data") or {}
        r21.check("queuedCount" in d, "data.queuedCount 存在")
        r21.check(d.get("queuedCount") == 1, f"queuedCount=1 (实际 {d.get('queuedCount')})")
        r21.check("message" in d, "data.message 存在")
        print_result(r21)

        section("模块六：分类管理 (5 接口)")

        r24 = Result("分类管理列表", "GET", "/api/v1/admin/categories")
        r = c.get("/api/v1/admin/categories?type=tag", headers=auth)
        r24.response = r.json()
        r24.check(r.status_code == 200, f"HTTP 200 (实际 {r.status_code})")
        d = r24.response.get("data") or {}
        r24.check("list" in d and "total" in d, "data.list + data.total 存在")
        r24.check(d.get("total") == 20, f"tag 分类数=20 (实际 {d.get('total')})")
        if d.get("list"):
            item = d["list"][0]
            for k in ["categoryId", "type", "name", "iconUrl", "photoCount", "createdAt"]:
                r24.check(k in item, f"list[0].{k} 存在")
        print_result(r24)

        r25 = Result("添加分类", "POST", "/api/v1/admin/categories")
        cat_name = f"测试分类_{TAG}"
        r = c.post("/api/v1/admin/categories",
                   json={"type": "tag", "name": cat_name, "iconUrl": "/static/icon/test.png"},
                   headers=auth)
        r25.response = r.json()
        r25.check(r.status_code == 200, f"HTTP 200 (实际 {r.status_code})")
        r25.check(r25.response.get("code") == 200, f"code=200")
        r25.check("message" in r25.response, "message 存在")
        d = r25.response.get("data") or {}
        new_cat_id = d.get("categoryId")
        r25.check(isinstance(new_cat_id, int), f"data.categoryId 是整数 (实际 {new_cat_id})")
        r25.check(new_cat_id > 60, f"自定义 categoryId > 60 (实际 {new_cat_id})")
        print_result(r25)

        r26 = Result("修改分类", "PATCH", "/api/v1/admin/categories/{categoryId}")
        if new_cat_id:
            r = c.patch(f"/api/v1/admin/categories/{new_cat_id}",
                        json={"name": f"改名_{TAG}"}, headers=auth)
            r26.response = r.json()
            r26.check(r.status_code == 200, f"HTTP 200 (实际 {r.status_code})")
            r26.check(r26.response.get("code") == 200, f"code=200")
            r26.check("message" in r26.response, "message 存在")
        else:
            r26.check(False, "无 new_cat_id")
        print_result(r26)

        r27 = Result("删除分类", "DELETE", "/api/v1/admin/categories/{categoryId}")
        if new_cat_id:
            r = c.delete(f"/api/v1/admin/categories/{new_cat_id}", headers=auth)
            r27.response = r.json()
            r27.check(r.status_code == 200, f"HTTP 200 (实际 {r.status_code})")
            r27.check(r27.response.get("code") == 200, f"code=200")
            r27.check("message" in r27.response, "message 存在")
            verify_resp = c.delete(f"/api/v1/admin/categories/{new_cat_id}", headers=auth)
            r27.check(verify_resp.json().get("code") == 404, f"重删返回 404 (实际 {verify_resp.json().get('code')})")
        else:
            r27.check(False, "无 new_cat_id")
        print_result(r27)

        r28 = Result("重置分类", "POST", "/api/v1/admin/categories/reset")
        c.post("/api/v1/admin/categories",
               json={"type": "tag", "name": f"待清除_{TAG}"}, headers=auth)
        r = c.post("/api/v1/admin/categories/reset", json={"confirm": True}, headers=auth)
        r28.response = r.json()
        r28.check(r.status_code == 200, f"HTTP 200 (实际 {r.status_code})")
        r28.check(r28.response.get("code") == 200, f"code=200")
        d = r28.response.get("data") or {}
        r28.check(d.get("resetCount") == 60, f"resetCount=60 (实际 {d.get('resetCount')})")
        r28.check("removedCount" in d, "data.removedCount 存在")
        r28.check(isinstance(d.get("removedCount"), int) and d["removedCount"] >= 1,
                  f"removedCount ≥ 1 (实际 {d.get('removedCount')})")
        print_result(r28)

        section("汇总")
        all_results = [r1, r2, r3, r4, r5, r6, r7, r8, r9, r10, r11, r12, r13, r14, r15, r16,
                       r17, r18, r19, r20, r21, r24, r25, r26, r27, r28]
        passed = sum(1 for x in all_results if x.passed)
        print(f"通过：{passed} / {len(all_results)}")
        for x in all_results:
            if not x.passed:
                print(f"  [FAIL] {x.method} {x.path}: {x.name}")


if __name__ == "__main__":
    main()

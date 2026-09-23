"""演示接收器只验证密码学及输入边界；真实去重另由 check_webhooks 联调。"""
import base64
import hmac
import hashlib
import json
import unittest
import uuid
from webhook_receiver import verify


class ReceiverVerificationTest(unittest.TestCase):
    """独立签名生成与错误输入验证。@author owlzhangfq@gmail.com"""
    def test_raw_signature_and_time_window(self):
        key=bytes(32);event_id=str(uuid.uuid4());body=json.dumps({'eventId':event_id,'payloadVersion':1,'tenantId':'demo','text':'中文'},ensure_ascii=False).encode()
        signature='v1,'+base64.b64encode(hmac.new(key,event_id.encode()+b'.12345.'+body,hashlib.sha256).digest()).decode()
        headers={'webhook-id':event_id,'webhook-timestamp':'12345','webhook-signature':signature}
        self.assertEqual(verify(key,headers,body,12345)['eventId'],event_id)
        for raw, now in ((body+b' ',12345),(body,12646),(body,12044)):
            with self.assertRaises(ValueError):verify(key,headers,raw,now)
        with self.assertRaises(ValueError):verify(bytes([1])*32,headers,body,12345)
        headers['webhook-signature']='v1,old '+signature
        self.assertEqual(verify(key,headers,body,12345)['eventId'],event_id)


if __name__=='__main__':unittest.main()

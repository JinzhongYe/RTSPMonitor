import unittest
from importlib.machinery import SourceFileLoader
inspect = SourceFileLoader('check_media', __file__.replace('test_check_media.py', 'check-media.py')).load_module().inspect
class MediaChecks(unittest.TestCase):
    def fixture(self):
        return {'streams': [{'codec_type':'video', 'codec_name':'hevc', 'index':0, 'width':1920, 'height':1080}], 'packets':[{'stream_index':0, 'flags':'K_', 'dts_time':'0'}, {'stream_index':0, 'flags':'__', 'dts_time':'0.04'}], 'format':{'duration':'0.08'}}
    def test_valid_hevc(self):
        self.assertEqual(inspect(self.fixture())['codec'], 'hevc')
    def test_reject_non_key_start(self):
        d=self.fixture(); d['packets'][0]['flags']='__'
        with self.assertRaises(AssertionError): inspect(d)
    def test_reject_non_monotonic_dts(self):
        d=self.fixture(); d['packets'][1]['dts_time']='0'
        with self.assertRaises(AssertionError): inspect(d)
if __name__ == '__main__': unittest.main()

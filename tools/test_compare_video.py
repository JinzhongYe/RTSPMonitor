import unittest
from importlib.machinery import SourceFileLoader
module = SourceFileLoader('compare_video', __file__.replace('test_compare_video.py', 'compare-video.py')).load_module()
class VideoCopyChecks(unittest.TestCase):
    def test_matches_loop_across_wrap(self):
        self.assertEqual(module.matching_offset(['a', 'b', 'c'], ['c', 'a', 'b', 'c']), 2)
    def test_rejects_changed_video(self):
        with self.assertRaises(AssertionError): module.matching_offset(['a', 'b'], ['a', 'x'])
    def test_extracts_mp4_slices_ignoring_parameter_nals(self):
        def dump(data): return '\n00000000: ' + data.hex(' ') + '  ...\n'
        document={'streams':[{'codec_name':'h264', 'extradata':dump(bytes([1,0,0,0,3]))}], 'packets':[{'data':dump(bytes.fromhex('0000000267aa0000000265bb'))}]}
        self.assertEqual(len(module.vcl_hashes(document)), 1)
    def test_aac_hashes_preserve_access_unit_order(self):
        d={'streams':[{'codec_name':'aac'}], 'packets':[{'data':'00000000: 0102  ..'}, {'data':'00000000: 0304  ..'}]}
        values=module.aac_hashes(d)
        self.assertEqual(len(values),2)
        self.assertNotEqual(values[0],values[1])
    def test_rejects_truncated_nal(self):
        document={'streams':[{'codec_name':'h264', 'extradata':'00000000: 0100000003  ...'}], 'packets':[{'data':'00000000: 0000000265  ...'}]}
        with self.assertRaises(AssertionError): module.vcl_hashes(document)
if __name__ == '__main__': unittest.main()

"""Validate captured login-form IME evidence; this module does not collect it.

Geometry is in the same physical display coordinates as the captured screenshot.
The caller must independently measure system-bar and actual IME bounds and label
size at the same display/font configuration, with the keyboard closed.
"""
import re


def _rect(value):
    if not isinstance(value, str) or not re.fullmatch(r'\[-?\d+,-?\d+\]\[-?\d+,-?\d+\]', value):
        raise ValueError('Invalid rectangle')
    x1, y1, x2, y2 = map(int, re.findall(r'-?\d+', value))
    if x1 >= x2 or y1 >= y2:
        raise ValueError('Empty rectangle')
    return x1, y1, x2, y2


def verify_login_ime_evidence(record):
    """Reject incomplete evidence; passing geometry alone is not a device test."""
    if not isinstance(record, dict):
        raise ValueError('Missing login evidence')
    for name in ('width_px', 'height_px', 'system_top_px', 'keyboard_top_px'):
        if type(record.get(name)) is not int:
            raise ValueError('Missing measured display geometry')
    width, height = record['width_px'], record['height_px']
    top, bottom = record['system_top_px'], record['keyboard_top_px']
    if not (0 <= top < bottom < height and width > 0):
        raise ValueError('Invalid system or keyboard geometry')
    if record.get('ime_visible') is not True or record.get('keyboard_screenshot_verified') is not True:
        raise ValueError('Keyboard visibility is not established')
    if record.get('confirm_tapped_with_ime') is not True:
        raise ValueError('Confirmation was not tapped while the keyboard was open')
    draft = record.get('draft')
    if not isinstance(draft, str) or not draft or record.get('reopened_draft') != draft:
        raise ValueError('Reopened draft does not match')
    label = _rect(record.get('label_bounds'))
    reference = _rect(record.get('unclipped_label_bounds'))
    editor = _rect(record.get('editor_bounds'))
    action = _rect(record.get('confirm_bounds'))
    for rect in (label, editor, action):
        if not (0 <= rect[0] < rect[2] <= width and top <= rect[1] < rect[3] <= bottom):
            raise ValueError('Login control overlaps a system bar or keyboard')
    if label[2] - label[0] < reference[2] - reference[0] or label[3] - label[1] < reference[3] - reference[1]:
        raise ValueError('Login label is clipped')
    if type(record.get('min_action_px')) is not int or record['min_action_px'] <= 0:
        raise ValueError('Missing measured minimum action size')
    if action[2] - action[0] < record['min_action_px'] or action[3] - action[1] < record['min_action_px']:
        raise ValueError('Login confirmation target is too small')

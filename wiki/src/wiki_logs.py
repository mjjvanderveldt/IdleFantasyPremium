import logging


def default_warning(value: str, warning_type: str, expected_file: str = "strings.xml", missing_element: str = "name") -> str:
    return f"The {warning_type} `{value}` did not have a defined {missing_element} in {expected_file}"


class WikiLogger:
    def __init__(self):
        self._warned_ids: set[str] = set()

    def warn_by_id(self, warn_id: str, log_message: str, level = logging.WARNING) -> None:
        if warn_id in self._warned_ids:
            return
        logging.log(level, log_message)
        self._warned_ids.add(warn_id)


LOGGER = WikiLogger()

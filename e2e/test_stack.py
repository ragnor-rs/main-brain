"""The stack a dev deploy just brought up is actually running.

Shallow on purpose, and the only thing this suite can honestly assert until there are
services with behaviour. What it does cover is the failure that wastes the most time: every
later check timing out one after another because a container exited at boot and nobody
looked. This says so in one line instead.
"""

import pytest

import stack


@pytest.mark.stack
def test_every_service_is_running():
    states = stack.states()
    not_running = {name: state for name, state in states.items() if state != "running"}

    assert not not_running, (
        f"These services are not running: {not_running}. The whole stack is {states}. "
        "`absent` means Compose has no container for it at all — either the deploy did not "
        "bring it up, or it was dropped from docker-compose.yml."
    )

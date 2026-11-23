# syntax=docker/dockerfile:1.7
FROM python:3.11-slim

WORKDIR /app

# The inference stack first, and on its own. It is by far the largest thing installed here
# and the least likely to change, so it earns the lowest layer: a bump to the web framework
# then costs a small reinstall rather than fetching the tensor library again.
COPY requirements-model.txt /app/requirements-model.txt

# The cache mount is what keeps the inference wheel a one-time download per builder rather
# than a cost on every build. It is not a layer: pip's download cache lives in the build cache
# and never reaches the image, so this costs nothing in image size.
#
# `--no-cache-dir` would defeat it exactly — that flag exists to stop pip's cache bloating a
# layer, which is the problem a cache mount already solves without also throwing the downloads
# away. The two together would re-fetch the whole tree every build and look like an
# optimisation while doing it.
RUN --mount=type=cache,target=/root/.cache/pip \
    pip install -r /app/requirements-model.txt

# Then the service's own dependencies, and only then the source — so editing a handler
# reinstalls nothing at all.
COPY requirements.txt /app/requirements.txt
RUN --mount=type=cache,target=/root/.cache/pip \
    pip install -r /app/requirements.txt

COPY model.py /app/model.py
COPY app.py /app/app.py

# No model is baked in. Weights are fetched at run time into a mounted cache, which keeps them
# out of an image that otherwise has to be rebuilt and shipped whenever the service changes —
# and keeps the image's size a property of the code rather than of the model behind it.
ENV HF_HOME=/models
EXPOSE 8000
ENTRYPOINT ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8000"]

FROM python:3.11-slim

ARG PORT=32666
ARG HOST=0.0.0.0

WORKDIR /code

COPY ./requirements.txt /code/requirements.txt
RUN pip install --no-cache-dir --upgrade -r /code/requirements.txt

COPY ./app /code/app

CMD uvicorn app.routes:app --host $HOST --port $PORT
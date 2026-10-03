.PHONY: test up down burst logs

test:
	./mvnw test

up:
	docker compose up --build -d

down:
	docker compose down -v

burst:
	./burst.sh http://localhost:8080

logs:
	docker compose logs -f app

package com.fxlock.quote;

import java.security.Principal;
import java.time.Clock;
import jakarta.validation.Valid;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import static com.fxlock.quote.Models.*;

@RestController
@RequestMapping("/quotes")
public class QuoteController {
	private final QuoteService service;
	private final Clock clock;
	private final Environment environment;

	public QuoteController(QuoteService service, Clock clock, Environment environment) {
		this.service = service;
		this.clock = clock;
		this.environment = environment;
	}

	private QuoteView view(Quote q) {
	    return new QuoteView(
	        q,
	        clock.instant(),
	        environment.acceptsProfiles(
	            Profiles.of("demo")
	        )
	    );
	}

	@PostMapping
	ResponseEntity<QuoteView> create(@Valid @RequestBody CreateQuoteRequest body, Principal principal) {
		Quote q = service.create(body, principal.getName());
		return ResponseEntity.created(java.net.URI.create("/quotes/" + q.quoteId())).body(view(q));
	}

	@GetMapping("/{id}")
	QuoteView get(@PathVariable String id, Principal principal) {
		return view(service.get(id, principal.getName()));
	}

	@PostMapping("/{id}/cancel")
	QuoteView cancel(@PathVariable String id, Principal principal) {
		return view(service.cancel(id, principal.getName()));
	}

	@PostMapping("/{id}/accept")
	SettlementResult accept(@PathVariable String id, @RequestHeader("Idempotency-Key") String key,
			Principal principal) {
		return service.accept(id, principal.getName(), key);
	}
}

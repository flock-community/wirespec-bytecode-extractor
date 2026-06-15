// src/test/kotlin/community/flock/wirespec/extractor/fixtures/wrapped/WrappedController.kt
package community.flock.wirespec.extractor.fixtures.wrapped

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.util.Optional
import java.util.concurrent.Callable

data class Item(val id: String)

@RestController
class WrappedController {

    @GetMapping("/raw")        fun raw(): Item = Item("x")
    @GetMapping("/entity")     fun entity(): ResponseEntity<Item> = ResponseEntity.ok(Item("x"))
    @GetMapping("/optional")   fun opt(): Optional<Item> = Optional.empty()
    @GetMapping("/mono")       fun mono(): Mono<Item> = Mono.empty()
    @GetMapping("/flux")       fun flux(): Flux<Item> = Flux.empty()
    @GetMapping("/callable")   fun callable(): Callable<Item> = Callable { Item("x") }
    @GetMapping("/void")       fun voided() {}
    @GetMapping("/monovoid")   fun monoVoid(): Mono<Void> = Mono.empty()

    @PostMapping("/created")
    @ResponseStatus(HttpStatus.CREATED)
    fun created(): Item = Item("x")
}

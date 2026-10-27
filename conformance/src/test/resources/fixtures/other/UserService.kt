package com.example.service

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class User(val id: Long, val name: String, val email: String?, val roles: Set<Role> = emptySet())

enum class Role { ADMIN, USER, GUEST }

sealed interface Result<out T> {
    data class Ok<T>(val value: T) : Result<T>
    data class Err(val message: String, val cause: Throwable? = null) : Result<Nothing>
}

class UserService(private val repository: UserRepository, private val clock: () -> Long = System::currentTimeMillis) {
    fun find(id: Long): Result<User> = repository.byId(id)?.let { Result.Ok(it) } ?: Result.Err("user $id not found")

    fun names(users: Flow<User>): Flow<String> = users.map { user -> user.name.uppercase() }

    fun describe(user: User): String = when {
        Role.ADMIN in user.roles -> "admin ${user.name}"
        user.email.isNullOrBlank() -> "anonymous"
        else -> user.email
    }

    companion object { const val LIMIT = 100 }
}

interface UserRepository { fun byId(id: Long): User? }

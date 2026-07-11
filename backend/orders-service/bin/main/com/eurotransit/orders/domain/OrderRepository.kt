package com.eurotransit.orders.domain

import org.springframework.data.r2dbc.repository.R2dbcRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface OrderRepository : R2dbcRepository<Order, UUID>

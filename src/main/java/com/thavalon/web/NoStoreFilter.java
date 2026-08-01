package com.thavalon.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Nothing is cacheable.
 *
 * <p>A stale role card is the worst possible bug in this app — a player seeing a previous game's
 * role, or a browser replaying a cached lobby, would silently corrupt a game. The assets are a
 * few kilobytes, so refetching them costs nothing worth optimising for.
 */
@Component
public class NoStoreFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
        response.setHeader("Pragma", "no-cache");
        response.setHeader("Expires", "0");
        chain.doFilter(request, response);
    }
}

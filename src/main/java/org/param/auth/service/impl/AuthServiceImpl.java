/*
 * Copyright (c) 2024 PARAM SOFT. All rights reserved.
 * 
 * This software and its documentation (the "Software") are confidential and proprietary to PARAM SOFT.
 * The Software is protected by copyright, trade secret, and other intellectual property laws. 
 * Unauthorized use, reproduction, modification, distribution, or disclosure of the Software, 
 * in whole or in part, is strictly prohibited without prior written consent from PARAM SOFT.
 * The Software is provided "as-is" without any express or implied warranty of any kind, 
 * including but not limited to the warranties of merchant ability, fitness for a particular purpose, 
 * or non infringement. You may use the Software only in accordance with the terms of 
 * the applicable license agreement. 
 *
 * For more information, including licensing inquiries or support, 
 * please contact: PARAM SOFT - https://www.paramsoft.org
 */
package org.param.auth.service.impl;

import java.time.LocalDateTime;

import org.param.auth.dto.AuthResponse;
import org.param.auth.dto.ForgotPasswordRequest;
import org.param.auth.dto.LoginRequest;
import org.param.auth.dto.RefreshTokenRequest;
import org.param.auth.dto.RegisterRequest;
import org.param.auth.dto.ResetPasswordRequest;
import org.param.auth.exception.InvalidCredentialsException;
import org.param.auth.exception.InvalidOtpException;
import org.param.auth.exception.InvalidTokenException;
import org.param.auth.exception.UserAlreadyExistsException;
import org.param.auth.exception.UserNotFoundException;
import org.param.auth.model.OtpToken;
import org.param.auth.model.RefreshToken;
import org.param.auth.model.User;
import org.param.auth.repository.OtpTokenRepository;
import org.param.auth.repository.RefreshTokenRepository;
import org.param.auth.repository.UserRepository;
import org.param.auth.service.AuthService;
import org.param.auth.service.EmailService;
import org.param.auth.service.JwtService;
import org.param.auth.util.EmailFormatter;
import org.param.auth.util.OtpUtil;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Author: PARAMESHWARAN PV
 * Date: 27-Jun-2025 : 1:49:49 AM
 * Since: 1.0.0
 * @See #
 */

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

	private final UserRepository userRepository;
	private final RefreshTokenRepository refreshTokenRepository;
	private final OtpTokenRepository otpTokenRepository;
	private final JwtService jwtService;
	private final PasswordEncoder passwordEncoder;
	private final EmailService emailService;

	private final int OTP_EXPIRY_MINUTES = 10;

	@Override
	public void register(RegisterRequest request) {
		if (userRepository.existsByEmail(request.getEmail())) {
			throw new UserAlreadyExistsException("Email already registered");
		}

		User user = User.builder().email(request.getEmail()).password(passwordEncoder.encode(request.getPassword()))
				.fullName(request.getFullName()).mobileNo(request.getMobileNo()).roles(request.getRoles())
				.isVerified(true).build();

		userRepository.save(user);
	}

	@Override
	public AuthResponse login(LoginRequest request) {
		User user = userRepository.findByEmail(request.getEmail())
				.orElseThrow(() -> new InvalidCredentialsException("Invalid credentials"));

		if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
			throw new InvalidCredentialsException("Invalid credentials");
		}

		String accessToken = jwtService.generateAccessToken(user.getEmail(), user.getRoles());
		String refreshTokenStr = jwtService.generateRefreshToken(user.getEmail());

		// Save or update refresh token in DB
		RefreshToken refreshToken = refreshTokenRepository.findByUser(user)
				.orElse(RefreshToken.builder().user(user).build());

		refreshToken.setToken(refreshTokenStr);
		refreshToken.setExpiryDate(LocalDateTime.now().plusDays(7));
		refreshTokenRepository.save(refreshToken);

		return new AuthResponse(accessToken, refreshTokenStr);
	}

	@Override
	public AuthResponse refreshToken(RefreshTokenRequest request) {
		String refreshTokenStr = request.getRefreshToken();

		if (!jwtService.isTokenValid(refreshTokenStr)) {
			throw new InvalidTokenException("Invalid refresh token");
		}

		String email = jwtService.extractUsername(refreshTokenStr);
		User user = userRepository.findByEmail(email).orElseThrow(() -> new UserNotFoundException("User not found"));

		RefreshToken refreshToken = refreshTokenRepository.findByUser(user)
				.orElseThrow(() -> new InvalidTokenException("Refresh token not found"));

		if (!refreshToken.getToken().equals(refreshTokenStr)
				|| refreshToken.getExpiryDate().isBefore(LocalDateTime.now())) {
			throw new InvalidTokenException("Refresh token expired or invalid");
		}

		String newAccessToken = jwtService.generateAccessToken(email, user.getRoles());

		return new AuthResponse(newAccessToken, refreshTokenStr); // reuse existing refresh token
	}

	@Override
	public void forgotPassword(ForgotPasswordRequest request) {
		String email = request.getEmail();

		// Always respond same, no user enumeration
		userRepository.findByEmail(email).ifPresent(user -> {
			// Generate OTP
			String otp = OtpUtil.generateOtp();

			// In prod: hash OTP before storing (omitted here for clarity)
			OtpToken otpToken = OtpToken.builder().email(email).otp(otp)
					.expiryTime(LocalDateTime.now().plusMinutes(OTP_EXPIRY_MINUTES)).used(false).build();

			otpTokenRepository.save(otpToken);

			// Send OTP via email (async recommended)
			String message = EmailFormatter.buildOtpMessage(otp);
			emailService.sendEmail(email, "Password Reset OTP", message);
		});
	}

	@Override
	public void resetPassword(ResetPasswordRequest request) {
		OtpToken otpToken = otpTokenRepository.findTopByEmailOrderByExpiryTimeDesc(request.getEmail())
				.orElseThrow(() -> new InvalidOtpException("Invalid OTP"));

		if (otpToken.isUsed() || otpToken.getExpiryTime().isBefore(LocalDateTime.now())) {
			throw new InvalidOtpException("OTP expired or already used");
		}

		if (!otpToken.getOtp().equals(request.getOtp())) {
			throw new InvalidOtpException("Invalid OTP");
		}

		User user = userRepository.findByEmail(request.getEmail())
				.orElseThrow(() -> new UserNotFoundException("User not found"));

		user.setPassword(passwordEncoder.encode(request.getNewPassword()));
		userRepository.save(user);

		otpToken.setUsed(true);
		otpTokenRepository.save(otpToken);
	}

}

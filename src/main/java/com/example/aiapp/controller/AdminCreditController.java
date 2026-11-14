package com.example.aiapp.controller;

import com.example.aiapp.domain.vo.PageVO;
import com.example.aiapp.service.AdminUserService;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/credits")
public class AdminCreditController {
    private final AdminUserService service;
    public AdminCreditController(AdminUserService service){this.service=service;}
    @GetMapping public PageVO<AdminUserService.CreditUserView> list(
            @RequestParam(defaultValue="1") @Min(1) long page,
            @RequestParam(defaultValue="20") @Min(1) @Max(100) long size,
            @RequestParam(defaultValue="") @Size(max=254) String keyword,
            @RequestParam(defaultValue="") @Pattern(regexp="|ACTIVE|DISABLED") String status){
        return service.creditUsers(page,size,keyword,status);
    }
}

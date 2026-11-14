package com.example.aiapp.controller;

import com.example.aiapp.service.AdminUserService;
import com.example.aiapp.domain.vo.PageVO;
import com.example.aiapp.utils.CurrentUserUtils;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController @RequestMapping("/admin/users")
public class AdminUserController {
    private final AdminUserService service;
    public AdminUserController(AdminUserService service){this.service=service;}
    @GetMapping public PageVO<AdminUserService.UserView> list(@RequestParam(defaultValue="1") @Min(1) long page,
            @RequestParam(defaultValue="20") @Min(1) @Max(100) long size,
            @RequestParam(defaultValue="") @Size(max=254) String keyword,
            @RequestParam(defaultValue="") @Pattern(regexp="|ACTIVE|DISABLED") String status){return service.list(page,size,keyword,status);}
    @GetMapping("/{id}") public AdminUserService.UserView detail(@PathVariable long id){return service.detail(id);}
    @GetMapping("/{id}/credits") public AdminUserService.CreditView credits(@PathVariable long id){return service.credits(id);}
    @GetMapping("/{id}/ledger") public List<AdminUserService.LedgerView> ledger(@PathVariable long id,
            @RequestParam(defaultValue="") @Size(max=64) String type,@RequestParam(defaultValue="50") @Min(1) @Max(100) int limit){return service.ledger(id,type,limit);}
    @PostMapping("/{id}/credits/grants") public AdminUserService.CreditView grant(@PathVariable long id,
            @Valid @RequestBody AdminUserService.GrantRequest request){return service.grant(CurrentUserUtils.currentUserId(),id,request);}
    @PostMapping("/{id}/credits/deductions") public AdminUserService.CreditView deduct(@PathVariable long id,
            @Valid @RequestBody AdminUserService.DeductRequest request){return service.deduct(CurrentUserUtils.currentUserId(),id,request);}
}
